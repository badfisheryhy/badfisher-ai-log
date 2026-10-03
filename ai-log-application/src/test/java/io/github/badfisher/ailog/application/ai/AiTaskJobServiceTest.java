package io.github.badfisher.ailog.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import io.github.badfisher.ailog.analysis.ai.AiEvidenceBuilder;
import io.github.badfisher.ailog.analysis.ai.EvidenceHashGenerator;
import io.github.badfisher.ailog.application.ai.AiTaskJobService.DispatchResult;
import io.github.badfisher.ailog.domain.ai.AiAnalysisProvider;
import io.github.badfisher.ailog.domain.ai.AiAnalysisProviderRegistry;
import io.github.badfisher.ailog.domain.ai.AiAnalysisResult;
import io.github.badfisher.ailog.domain.ai.AiJudgement;
import io.github.badfisher.ailog.domain.ai.AiProviderException;
import io.github.badfisher.ailog.domain.ai.AiProviderException.ErrorType;
import io.github.badfisher.ailog.domain.issue.ProblemLevel;
import io.github.badfisher.ailog.domain.ai.AiTaskClaim;
import io.github.badfisher.ailog.domain.ai.AiTaskBlameRepository;
import io.github.badfisher.ailog.domain.ai.AiTaskEvidenceContext;
import io.github.badfisher.ailog.domain.ai.AiTaskRepository;
import io.github.badfisher.ailog.domain.ai.AiTaskSourceLocation;
import io.github.badfisher.ailog.domain.ai.GitBlameResult;
import io.github.badfisher.ailog.domain.ai.GitBlameTool;
import io.github.badfisher.ailog.domain.analysis.ActionableIssueSnapshot;
import io.github.badfisher.ailog.parser.security.SensitiveLogSanitizer;

/** AI 调度、失败终态和拒绝归还测试。 */
class AiTaskJobServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-28T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final AiTaskRepository repository = mock(AiTaskRepository.class);
    private final AiAnalysisProvider provider = mock(AiAnalysisProvider.class);
    private final ScheduledExecutorService leaseRenewalScheduler =
            mock(ScheduledExecutorService.class);
    @SuppressWarnings("rawtypes")
    private final ScheduledFuture leaseRenewal = mock(ScheduledFuture.class);

    @BeforeEach
    void singleTaskScope() {
        when(repository.findNextTaskId(anyLong())).thenReturn((Long) null);
        when(repository.findNextTaskId(0L)).thenReturn(12L);
        when(repository.findReadyItemIds(eq(12L), eq(0L), anyInt(), any()))
                .thenReturn(Collections.singletonList(11L));
    }

    @Test
    void dispatchesClaimAndPersistsSuccessfulAttempt() {
        AiTaskClaim claim = claim();
        when(repository.prepareTask(eq(12L), any())).thenReturn(true, false);
        when(repository.claimReadyItems(eq(12L), anyList(), eq("worker-1"), any(), any()))
                .thenReturn(Collections.singletonList(claim))
                .thenReturn(Collections.emptyList());
        when(repository.loadEvidence(claim, 3)).thenReturn(evidenceContext());
        when(repository.startAttempt(eq(claim), any(), any(), any(), any())).thenReturn(91L);
        AiAnalysisResult analysisResult = result();
        when(provider.analyze(any())).thenReturn(analysisResult);

        DispatchResult dispatch = service(Runnable::run).dispatch();

        assertThat(dispatch.getPreparedCount()).isEqualTo(1);
        assertThat(dispatch.getSubmittedCount()).isEqualTo(1);
        assertThat(dispatch.getSuccessCount()).isEqualTo(1);
        verify(repository).prepareTask(eq(12L), any());
        verify(repository).completeSuccess(eq(claim), eq(91L), eq(analysisResult),
                anyLong(), any());
    }

    @Test
    void writesBlameBeforeProviderCall() {
        AiTaskClaim claim = prepareSuccessfulDispatch();
        AiTaskBlameRepository blameRepository = mock(AiTaskBlameRepository.class);
        GitBlameTool blameTool = mock(GitBlameTool.class);
        AiTaskSourceLocation location = new AiTaskSourceLocation(
                "dev", "system", "module", "com.example.Service", 42);
        GitBlameResult blame = new GitBlameResult("Alice",
                LocalDateTime.of(2026, 8, 27, 9, 30));
        when(blameRepository.findSourceLocation(11L, 14L)).thenReturn(location);
        when(blameTool.tryBlame(location)).thenReturn(Optional.of(blame));
        when(blameRepository.updateBlame(eq(claim), eq(null), eq(null), eq(blame), any()))
                .thenReturn(true);

        service(Runnable::run, new AiTaskBlameEnrichmentService(
                blameRepository, blameTool)).dispatch();

        InOrder order = inOrder(blameRepository, provider);
        order.verify(blameRepository).updateBlame(eq(claim), eq(null), eq(null),
                eq(blame), any());
        order.verify(provider).analyze(any());
    }

    @Test
    void blameRepositoryFailureDoesNotPreventProviderCall() {
        prepareSuccessfulDispatch();
        AiTaskBlameRepository blameRepository = mock(AiTaskBlameRepository.class);
        GitBlameTool blameTool = mock(GitBlameTool.class);
        when(blameRepository.findSourceLocation(11L, 14L))
                .thenThrow(new IllegalStateException("database failed"));

        DispatchResult dispatch = service(Runnable::run,
                new AiTaskBlameEnrichmentService(blameRepository, blameTool)).dispatch();

        assertThat(dispatch.getSuccessCount()).isEqualTo(1);
        verify(provider).analyze(any());
    }

    @Test
    void emptyBlameDoesNotPreventProviderCall() {
        prepareSuccessfulDispatch();
        AiTaskBlameRepository blameRepository = mock(AiTaskBlameRepository.class);
        GitBlameTool blameTool = mock(GitBlameTool.class);
        AiTaskSourceLocation location = new AiTaskSourceLocation(
                "dev", "system", "module", "com.example.Service", 42);
        when(blameRepository.findSourceLocation(11L, 14L)).thenReturn(location);
        when(blameTool.tryBlame(location)).thenReturn(Optional.empty());

        DispatchResult dispatch = service(Runnable::run,
                new AiTaskBlameEnrichmentService(blameRepository, blameTool)).dispatch();

        assertThat(dispatch.getSuccessCount()).isEqualTo(1);
        verify(provider).analyze(any());
    }

    @Test
    void gitToolExceptionDoesNotPreventProviderCall() {
        prepareSuccessfulDispatch();
        AiTaskBlameRepository blameRepository = mock(AiTaskBlameRepository.class);
        GitBlameTool blameTool = mock(GitBlameTool.class);
        AiTaskSourceLocation location = new AiTaskSourceLocation(
                "dev", "system", "module", "com.example.Service", 42);
        when(blameRepository.findSourceLocation(11L, 14L)).thenReturn(location);
        when(blameTool.tryBlame(location)).thenThrow(new IllegalStateException("git failed"));

        DispatchResult dispatch = service(Runnable::run,
                new AiTaskBlameEnrichmentService(blameRepository, blameTool)).dispatch();

        assertThat(dispatch.getSuccessCount()).isEqualTo(1);
        verify(provider).analyze(any());
    }

    @Test
    void processes120GroupsInThreePagesBeforeCheckingForAnEmptyPage() {
        mockPagedTask(12L, 120);

        DispatchResult dispatch = service(Runnable::run).dispatch();

        assertThat(dispatch.getSuccessCount()).isEqualTo(120);
        verify(repository).findReadyItemIds(eq(12L), eq(0L), eq(50), any());
        verify(repository).findReadyItemIds(eq(12L), eq(50L), eq(50), any());
        verify(repository).findReadyItemIds(eq(12L), eq(100L), eq(50), any());
        verify(repository).findReadyItemIds(eq(12L), eq(120L), eq(50), any());
        verify(provider, times(120)).analyze(any());
    }

    @Test
    void stopsAt200GroupsWithoutReadingAFifthPage() {
        mockPagedTask(12L, 205);

        DispatchResult dispatch = service(Runnable::run).dispatch();

        assertThat(dispatch.getClaimedCount()).isEqualTo(200);
        assertThat(dispatch.getSuccessCount()).isEqualTo(200);
        verify(repository, times(4)).findReadyItemIds(eq(12L), anyLong(), eq(50), any());
        verify(repository, never()).findReadyItemIds(eq(12L), eq(200L), anyInt(), any());
        verify(provider, times(200)).analyze(any());
    }

    @Test
    void applies200GroupLimitIndependentlyToEachTask() {
        mockPagedTask(12L, 200);
        mockPagedTask(13L, 120);
        when(repository.findNextTaskId(12L)).thenReturn(13L);

        DispatchResult dispatch = service(Runnable::run).dispatch();

        assertThat(dispatch.getSuccessCount()).isEqualTo(320);
        verify(repository).prepareTask(eq(12L), any());
        verify(repository).prepareTask(eq(13L), any());
        verify(provider, times(320)).analyze(any());
    }

    @Test
    void continuesAcrossPagesAfterProviderFailureAndSchedulesSameItemRetry() {
        mockPagedTask(12L, 120);
        when(provider.analyze(any()))
                .thenThrow(new AiProviderException(ErrorType.RATE_LIMITED, "rate limited"))
                .thenReturn(result());

        DispatchResult dispatch = service(Runnable::run).dispatch();

        assertThat(dispatch.getClaimedCount()).isEqualTo(120);
        assertThat(dispatch.getSuccessCount()).isEqualTo(119);
        assertThat(dispatch.getFailureCount()).isEqualTo(1);
        assertThat(dispatch.getRetryScheduledCount()).isEqualTo(1);
        verify(provider, times(120)).analyze(any());
        verify(repository).completeFailure(
                org.mockito.ArgumentMatchers.argThat(item -> item.getItemId() == 1L),
                anyLong(), org.mockito.ArgumentMatchers.argThat(failure ->
                        failure.isRetryable()
                                && "RATE_LIMITED".equals(failure.getErrorType())),
                anyLong(), any());
        verify(repository).findReadyItemIds(eq(12L), eq(100L), eq(50), any());
    }

    @Test
    void continuesAcrossPagesAfterNonRetryableProviderFailure() {
        mockPagedTask(12L, 120);
        when(provider.analyze(any()))
                .thenThrow(new IllegalStateException("request rejected"))
                .thenReturn(result());

        DispatchResult dispatch = service(Runnable::run).dispatch();

        assertThat(dispatch.getFailureCount()).isEqualTo(1);
        assertThat(dispatch.getSuccessCount()).isEqualTo(119);
        assertThat(dispatch.getRetryScheduledCount()).isZero();
        verify(provider, times(120)).analyze(any());
    }

    @Test
    void claimsOnlyFiveItemsWhileFirstWorkerGroupIsStillRunning() throws Exception {
        mockPagedTask(12L, 50);
        CountDownLatch started = new CountDownLatch(5);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(5);
        ExecutorService job = Executors.newSingleThreadExecutor();
        when(provider.analyze(any())).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test worker release timed out");
            }
            return result();
        });
        try {
            AiTaskJobService service = service(workers);
            Future<DispatchResult> result = job.submit(service::dispatch);
            assertThat(started.await(5L, TimeUnit.SECONDS)).isTrue();
            verify(repository, times(1)).claimReadyItems(
                    eq(12L), eq(Arrays.asList(1L, 2L, 3L, 4L, 5L)), any(), any(), any());
            verify(repository, times(1)).claimReadyItems(
                    anyLong(), anyList(), any(), any(), any());

            release.countDown();
            assertThat(result.get(10L, TimeUnit.SECONDS).getSuccessCount()).isEqualTo(50);
        } finally {
            release.countDown();
            job.shutdownNow();
            workers.shutdownNow();
        }
    }

    @Test
    void renewsLeaseWhileProviderCallIsRunning() {
        AiTaskClaim claim = claim();
        when(repository.claimReadyItems(eq(12L), anyList(), eq("worker-1"), any(), any()))
                .thenReturn(Collections.singletonList(claim), Collections.emptyList());
        when(repository.loadEvidence(claim, 3)).thenReturn(evidenceContext());
        when(repository.startAttempt(eq(claim), any(), any(), any(), any())).thenReturn(91L);
        when(provider.analyze(any())).thenAnswer(invocation -> {
            Runnable renewalTask = leaseRenewalTask();
            renewalTask.run();
            return result();
        });

        service(Runnable::run).dispatch();

        verify(leaseRenewalScheduler).scheduleAtFixedRate(any(Runnable.class),
                eq(60L), eq(60L), eq(TimeUnit.SECONDS));
        verify(repository).renewLease(eq(claim), any(), any());
        verify(leaseRenewal).cancel(false);
    }

    @Test
    void doesNotPersistProviderFailureAfterLeaseRenewalFails() {
        AiTaskClaim claim = claim();
        when(repository.claimReadyItems(eq(12L), anyList(), eq("worker-1"), any(), any()))
                .thenReturn(Collections.singletonList(claim), Collections.emptyList());
        when(repository.loadEvidence(claim, 3)).thenReturn(evidenceContext());
        when(repository.startAttempt(eq(claim), any(), any(), any(), any())).thenReturn(91L);
        doThrow(new IllegalStateException("lease lost"))
                .when(repository).renewLease(eq(claim), any(), any());
        when(provider.analyze(any())).thenAnswer(invocation -> {
            try {
                leaseRenewalTask().run();
            } catch (IllegalStateException ignored) {
                // 模拟调度线程记录续租失败后，Provider 调用自身也发生异常。
            }
            throw new AiProviderException(ErrorType.INVALID_RESPONSE, "invalid response");
        });

        assertThatThrownBy(() -> service(Runnable::run).dispatch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("lease lost");
        verify(repository, never()).completeFailure(any(), anyLong(), any(), anyLong(), any());
        verify(repository, never()).completeSuccess(any(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void marksInvalidResponseAsTerminalAttemptFailure() {
        AiTaskClaim claim = claim();
        when(repository.claimReadyItems(eq(12L), anyList(), eq("worker-1"), any(), any()))
                .thenReturn(Collections.singletonList(claim), Collections.emptyList());
        when(repository.loadEvidence(claim, 3)).thenReturn(evidenceContext());
        when(repository.startAttempt(eq(claim), any(), any(), any(), any())).thenReturn(92L);
        when(provider.analyze(any())).thenThrow(new AiProviderException(
                ErrorType.INVALID_RESPONSE, "invalid response"));

        service(Runnable::run).dispatch();

        verify(repository).completeFailure(eq(claim), eq(92L),
                org.mockito.ArgumentMatchers.argThat(failure ->
                        !failure.isRetryable()
                                && "INVALID_RESPONSE".equals(failure.getErrorType())
                                && "invalid response".equals(failure.getErrorMessage())),
                anyLong(), any());
    }

    @Test
    void continuesWithNextItemAfterProviderFailure() {
        AiTaskClaim first = claim(11L);
        AiTaskClaim second = claim(12L);
        AiAnalysisResult analysisResult = result();
        when(repository.findReadyItemIds(eq(12L), eq(0L), eq(50), any()))
                .thenReturn(Arrays.asList(11L, 12L));
        when(repository.claimReadyItems(eq(12L), anyList(), eq("worker-1"), any(), any()))
                .thenReturn(Arrays.asList(first, second), Collections.emptyList());
        when(repository.loadEvidence(any(), eq(3))).thenReturn(evidenceContext());
        when(repository.startAttempt(any(), any(), any(), any(), any()))
                .thenReturn(91L, 92L);
        when(provider.analyze(any()))
                .thenThrow(new AiProviderException(ErrorType.RATE_LIMITED, "rate limited"))
                .thenReturn(analysisResult);

        DispatchResult dispatch = service(Runnable::run).dispatch();

        assertThat(dispatch.getSubmittedCount()).isEqualTo(2);
        assertThat(dispatch.getSuccessCount()).isEqualTo(1);
        assertThat(dispatch.getFailureCount()).isEqualTo(1);
        assertThat(dispatch.getRetryScheduledCount()).isEqualTo(1);
        verify(provider, times(2)).analyze(any());
        verify(repository).completeFailure(eq(first), eq(91L),
                org.mockito.ArgumentMatchers.argThat(failure ->
                        failure.isRetryable()
                                && "RATE_LIMITED".equals(failure.getErrorType())),
                anyLong(), any());
        verify(repository).completeSuccess(eq(second), eq(92L), eq(analysisResult),
                anyLong(), any());
    }

    @Test
    void doesNotMisclassifyResultPersistenceFailureAsProviderFailure() {
        AiTaskClaim claim = claim();
        AiAnalysisResult analysisResult = result();
        when(repository.claimReadyItems(eq(12L), anyList(), eq("worker-1"), any(), any()))
                .thenReturn(Collections.singletonList(claim))
                .thenReturn(Collections.emptyList());
        when(repository.loadEvidence(claim, 3)).thenReturn(evidenceContext());
        when(repository.startAttempt(eq(claim), any(), any(), any(), any())).thenReturn(93L);
        when(provider.analyze(any())).thenReturn(analysisResult);
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).completeSuccess(eq(claim), eq(93L), eq(analysisResult),
                        anyLong(), any());

        assertThatThrownBy(() -> service(Runnable::run).dispatch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        verify(repository, never()).completeFailure(any(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void continuesToNextTaskWhenCurrentTaskHasNoDueItems() {
        when(repository.findReadyItemIds(eq(12L), anyLong(), anyInt(), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findNextTaskId(12L)).thenReturn(13L);
        mockPagedTask(13L, 1);

        DispatchResult dispatch = service(Runnable::run).dispatch();

        assertThat(dispatch.getSuccessCount()).isEqualTo(1);
        verify(repository, never()).claimReadyItems(eq(12L), anyList(), any(), any(), any());
        verify(repository).prepareTask(eq(13L), any());
    }

    @Test
    void releasesClaimWhenExecutorRejects() {
        AiTaskClaim claim = claim();
        when(repository.claimReadyItems(eq(12L), anyList(), eq("worker-1"), any(), any()))
                .thenReturn(Collections.singletonList(claim));
        Executor rejecting = command -> {
            throw new RejectedExecutionException("full");
        };

        DispatchResult dispatch = service(rejecting).dispatch();

        assertThat(dispatch.getRejectedCount()).isEqualTo(1);
        verify(repository).releaseClaim(eq(claim), eq("EXECUTOR_REJECTED"),
                eq("AI executor rejected the claimed item"), any());
    }

    @Test
    void recordsSanitizationFailureWithoutStartingAnAttemptOrCallingTheProvider() {
        AiTaskClaim claim = claim();
        when(repository.claimReadyItems(eq(12L), anyList(), eq("worker-1"), any(), any()))
                .thenReturn(Collections.singletonList(claim), Collections.emptyList());
        when(repository.loadEvidence(claim, 3)).thenReturn(evidenceContext());
        AiEvidenceSanitizer failingSanitizer = new AiEvidenceSanitizer(value -> {
            throw new IllegalStateException("password=must-not-leak");
        });

        DispatchResult dispatch = service(Runnable::run, failingSanitizer).dispatch();

        assertThat(dispatch.getFailureCount()).isEqualTo(1);
        verify(repository).failBeforeCall(eq(claim), eq("SANITIZE_FAILED"),
                org.mockito.ArgumentMatchers.argThat(message ->
                        message.contains("14") && !message.contains("must-not-leak")), any());
        verify(repository, never()).startAttempt(any(), any(), any(), any(), any());
        verify(provider, never()).analyze(any());
    }

    @Test
    void rejectsNonFiniteConfidenceBeforeItCanReachPersistence() {
        assertThatThrownBy(() -> result(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(Double.NEGATIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBothConfidenceRangeBoundaries() {
        assertThat(result(0D).getConfidence()).isZero();
        assertThat(result(1D).getConfidence()).isEqualTo(1D);
    }

    /** 模拟每页查询ID、临执行时认领，检查上一页全部落库后才允许查询下一页。 */
    private void mockPagedTask(long taskId, int totalItems) {
        List<Long> completedItems = Collections.synchronizedList(new ArrayList<Long>());
        when(repository.findReadyItemIds(eq(taskId), anyLong(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    long afterItemId = invocation.getArgument(1);
                    int limit = invocation.getArgument(2);
                    assertThat(completedItems).hasSize((int) afterItemId);
                    List<Long> ids = new ArrayList<Long>();
                    for (long id = afterItemId + 1; id <= totalItems && ids.size() < limit; id++) {
                        ids.add(Long.valueOf(id));
                    }
                    return ids;
                });
        when(repository.claimReadyItems(eq(taskId), anyList(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(1);
                    assertThat(ids).hasSizeLessThanOrEqualTo(5);
                    List<AiTaskClaim> claims = new ArrayList<AiTaskClaim>();
                    for (Long id : ids) {
                        claims.add(claim(id.longValue(), taskId));
                    }
                    return claims;
                });
        when(repository.loadEvidence(any(), eq(3))).thenReturn(evidenceContext());
        when(repository.startAttempt(any(), any(), any(), any(), any())).thenReturn(91L);
        when(provider.analyze(any())).thenReturn(result());
        doAnswer(invocation -> {
            AiTaskClaim item = invocation.getArgument(0);
            completedItems.add(item.getItemId());
            return null;
        }).when(repository).completeSuccess(
                org.mockito.ArgumentMatchers.argThat(item -> item != null && item.getAiTaskId() == taskId),
                anyLong(), any(), anyLong(), any());
        doAnswer(invocation -> {
            AiTaskClaim item = invocation.getArgument(0);
            completedItems.add(item.getItemId());
            return null;
        }).when(repository).completeFailure(
                org.mockito.ArgumentMatchers.argThat(item -> item != null && item.getAiTaskId() == taskId),
                anyLong(), any(), anyLong(), any());
    }

    @Test
    void changingDefaultProviderDoesNotReroutePersistedOpenAiClaim() {
        AiTaskClaim claim = prepareSuccessfulDispatch();
        AiAnalysisProvider deepseek = mock(AiAnalysisProvider.class);
        AiAnalysisProviderRegistry routes = new AiAnalysisProviderRegistry(Map.of(
                AiAnalysisProviderRegistry.routeKey("openai", "gpt-5.6-sol"), provider,
                AiAnalysisProviderRegistry.routeKey("deepseek", "deepseek-flash"), deepseek),
                "deepseek", "deepseek-flash");
        doReturn(leaseRenewal).when(leaseRenewalScheduler).scheduleAtFixedRate(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
        AiTaskJobService job = new AiTaskJobService(repository, routes, new AiEvidenceBuilder(),
                new AiEvidenceSanitizer(new SensitiveLogSanitizer()), new EvidenceHashGenerator(),
                new AiTaskBlameEnrichmentService(mock(AiTaskBlameRepository.class), mock(GitBlameTool.class)),
                Runnable::run, leaseRenewalScheduler, 50, 3, 5, 180, "worker-1", CLOCK);
        assertThat(job.dispatch().getSuccessCount()).isEqualTo(1);
        verify(provider).analyze(any());
        verify(deepseek, never()).analyze(any());
        verify(repository).completeSuccess(eq(claim), eq(91L), any(), anyLong(), any());
    }

    private AiTaskJobService service(Executor executor) {
        return service(executor, new AiEvidenceSanitizer(new SensitiveLogSanitizer()));
    }

    private AiTaskJobService service(Executor executor,
            AiTaskBlameEnrichmentService blameEnrichmentService) {
        doReturn(leaseRenewal).when(leaseRenewalScheduler).scheduleAtFixedRate(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
        return new AiTaskJobService(repository, registry(), new AiEvidenceBuilder(),
                new AiEvidenceSanitizer(new SensitiveLogSanitizer()),
                new EvidenceHashGenerator(), blameEnrichmentService,
                executor, leaseRenewalScheduler, 50, 3, 5, 180, "worker-1", CLOCK);
    }

    private AiTaskClaim prepareSuccessfulDispatch() {
        AiTaskClaim claim = claim();
        when(repository.prepareTask(eq(12L), any())).thenReturn(true, false);
        when(repository.claimReadyItems(eq(12L), anyList(), eq("worker-1"), any(), any()))
                .thenReturn(Collections.singletonList(claim))
                .thenReturn(Collections.emptyList());
        when(repository.loadEvidence(claim, 3)).thenReturn(evidenceContext());
        when(repository.startAttempt(eq(claim), any(), any(), any(), any())).thenReturn(91L);
        when(provider.analyze(any())).thenReturn(result());
        return claim;
    }

    private AiTaskJobService service(Executor executor, AiEvidenceSanitizer sanitizer) {
        return service(executor, sanitizer, 50, 5);
    }

    private AiTaskJobService service(Executor executor, AiEvidenceSanitizer sanitizer,
            int pageSize, int parallelism) {
        doReturn(leaseRenewal).when(leaseRenewalScheduler).scheduleAtFixedRate(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
        return new AiTaskJobService(repository, registry(), new AiEvidenceBuilder(),
                sanitizer,
                new EvidenceHashGenerator(), new AiTaskBlameEnrichmentService(
                        mock(AiTaskBlameRepository.class), mock(GitBlameTool.class)),
                executor, leaseRenewalScheduler,
                pageSize, 3, parallelism, 180, "worker-1", CLOCK);
    }

    private Runnable leaseRenewalTask() {
        org.mockito.ArgumentCaptor<Runnable> task =
                org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(leaseRenewalScheduler).scheduleAtFixedRate(task.capture(),
                anyLong(), anyLong(), eq(TimeUnit.SECONDS));
        return task.getValue();
    }

    private AiAnalysisProviderRegistry registry() {
        Map<String, AiAnalysisProvider> providers =
                new LinkedHashMap<String, AiAnalysisProvider>();
        providers.put(AiAnalysisProviderRegistry.routeKey("openai", "gpt-5.6-sol"), provider);
        return new AiAnalysisProviderRegistry(
                providers, "openai", "gpt-5.6-sol");
    }

    private static AiTaskClaim claim() {
        return claim(11L);
    }

    private static AiTaskClaim claim(long itemId) {
        return claim(itemId, 12L);
    }

    private static AiTaskClaim claim(long itemId, long taskId) {
        return new AiTaskClaim(itemId, taskId, 14L, "openai", "gpt-5.6-sol",
                "ai-issue-v2", "sanitizer-v1", 0, 3, "claim-1");
    }

    private static AiTaskEvidenceContext evidenceContext() {
        ActionableIssueSnapshot snapshot = new ActionableIssueSnapshot(
                14L, "prod", "demo", "order", "fingerprint", "fp-v2",
                "CODE", "HTTP", "IllegalStateException", "NullPointerException",
                "OrderService", "create", "create order failed", null,
                20L, LocalDateTime.of(2026, 8, 28, 9, 0),
                LocalDateTime.of(2026, 8, 28, 10, 0), 31L,
                "create order failed", "at OrderService.create");
        return new AiTaskEvidenceContext(snapshot, Collections.emptyList());
    }

    private static AiAnalysisResult result() {
        return result(0.9D);
    }

    private static AiAnalysisResult result(double confidence) {
        return new AiAnalysisResult(AiJudgement.CONFIRMED, ProblemLevel.HIGH,
                "CODE", "空指针", "创建订单时发生空指针", "调用栈定位到OrderService",
                "请求字段未校验", "订单创建失败", "增加非空校验", "重放失败请求",
                "未取得完整请求参数", null, confidence, true, null, "openai", "gpt-5.6-sol",
                "ai-issue-v2", "{\"result\":true}", "provider-request", "completed",
                Integer.valueOf(100), Integer.valueOf(20), Integer.valueOf(120));
    }
}
