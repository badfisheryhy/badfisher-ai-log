package io.github.badfisher.ailog.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.badfisher.ailog.analysis.ai.AiEvidenceBuilder;
import io.github.badfisher.ailog.analysis.ai.EvidenceHashGenerator;
import io.github.badfisher.ailog.domain.ai.AiAnalysisProvider;
import io.github.badfisher.ailog.domain.ai.AiAnalysisProviderRegistry;
import io.github.badfisher.ailog.domain.ai.AiAnalysisResult;
import io.github.badfisher.ailog.domain.ai.AiCallFailure;
import io.github.badfisher.ailog.domain.ai.AiJudgement;
import io.github.badfisher.ailog.domain.ai.AiProviderException;
import io.github.badfisher.ailog.domain.issue.ProblemLevel;
import io.github.badfisher.ailog.domain.ai.AiTaskBlameRepository;
import io.github.badfisher.ailog.domain.ai.AiTaskClaim;
import io.github.badfisher.ailog.domain.ai.AiTaskEvidenceContext;
import io.github.badfisher.ailog.domain.ai.AiTaskRepository;
import io.github.badfisher.ailog.domain.ai.GitBlameTool;
import io.github.badfisher.ailog.domain.analysis.ActionableIssueSnapshot;
import io.github.badfisher.ailog.parser.security.SensitiveLogSanitizer;

/** 使用假 Provider 验证显式重跑与普通调度的执行、失败和持久化边界。 */
class AiTaskRerunExecutionTest {

    private static final long OPERATION_ID = 40L;
    private static final String EXECUTION_TOKEN = "rerun-execution";
    private static final long ITEM_ID = 11L;
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-09T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final AiTaskRepository repository = mock(AiTaskRepository.class);
    private final AiAnalysisProvider provider = mock(AiAnalysisProvider.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
    private final AiTaskClaim claim = new AiTaskClaim(ITEM_ID, 12L, 14L,
            "openai", "gpt-5.6-sol", "ai-issue-v2",
            "sanitizer-v1", 7, 3, "item-claim");

    private AiTaskJobService service;

    @BeforeEach
    void prepareRerunAfterHistoricalAttemptsExhausted() {
        doReturn(renewal).when(scheduler).scheduleAtFixedRate(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
        when(repository.claimRerunItem(eq(OPERATION_ID), eq(EXECUTION_TOKEN),
                eq(ITEM_ID), eq("worker"), any(), any())).thenReturn(claim);
        when(repository.loadEvidence(claim, 3)).thenReturn(evidence());
        when(repository.startRerunAttempt(eq(claim), eq(OPERATION_ID), eq(EXECUTION_TOKEN),
                any(), any(), any(), any())).thenReturn(91L);
        AiAnalysisProviderRegistry registry = new AiAnalysisProviderRegistry(
                Collections.singletonMap(
                        AiAnalysisProviderRegistry.routeKey("openai", "gpt-5.6-sol"), provider),
                "openai", "gpt-5.6-sol");
        service = new AiTaskJobService(repository, registry, new AiEvidenceBuilder(),
                new AiEvidenceSanitizer(new SensitiveLogSanitizer()),
                new EvidenceHashGenerator(), new AiTaskBlameEnrichmentService(
                        mock(AiTaskBlameRepository.class), mock(GitBlameTool.class)),
                Runnable::run, scheduler,
                50, 3, 5, 180, "worker", CLOCK);
    }

    @Test
    void successUsesOriginalClaimAndRerunPersistenceAfterAttemptLimit() {
        AiAnalysisResult result = result();
        when(provider.analyze(any())).thenReturn(result);

        AiTaskJobService.RerunExecutionResult outcome = rerun();

        assertThat(outcome.isExecuted()).isTrue();
        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.getItemId()).isEqualTo(ITEM_ID);
        assertThat(claim.getAttemptCount()).isGreaterThan(claim.getMaxAttempts());
        verify(repository).completeRerunSuccess(eq(claim), eq(OPERATION_ID),
                eq(EXECUTION_TOKEN), eq(91L), eq(result), anyLong(), any());
        verify(repository, never()).startAttempt(any(), any(), any(), any(), any());
        verify(repository, never()).completeSuccess(any(), anyLong(), any(), anyLong(), any());
        verify(renewal).cancel(false);
    }

    @Test
    void transientProviderFailureIsTerminalAndDoesNotUseNormalRetryPersistence() {
        when(provider.analyze(any())).thenThrow(new AiProviderException(
                AiProviderException.ErrorType.NETWORK_FAILURE, "temporary transport failure"));

        AiTaskJobService.RerunExecutionResult outcome = rerun();

        assertThat(outcome.isExecuted()).isTrue();
        assertThat(outcome.isSuccess()).isFalse();
        ArgumentCaptor<AiCallFailure> failure = ArgumentCaptor.forClass(AiCallFailure.class);
        verify(repository).completeRerunFailure(eq(claim), eq(OPERATION_ID),
                eq(EXECUTION_TOKEN), eq(91L), failure.capture(), anyLong(), any());
        assertThat(failure.getValue().isRetryable()).isFalse();
        assertThat(failure.getValue().getErrorType()).isEqualTo("NETWORK_FAILURE");
        verify(provider).analyze(any());
        verify(repository, never()).completeFailure(any(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void evidenceFailureStopsBeforeHttpAndUsesRerunFailurePersistence() {
        when(repository.loadEvidence(claim, 3))
                .thenThrow(new IllegalStateException("source evidence missing"));

        assertThat(rerun().isSuccess()).isFalse();

        verify(repository).failRerunBeforeCall(eq(claim), eq(OPERATION_ID),
                eq(EXECUTION_TOKEN), eq("PREPARE_FAILED"), any(), any());
        verify(provider, never()).analyze(any());
        verify(repository, never()).startRerunAttempt(
                any(), anyLong(), any(), any(), any(), any(), any());
        verify(repository, never()).failBeforeCall(any(), any(), any(), any());
    }

    @Test
    void successfulHttpWithFailedDatabaseWritePropagatesForCompensation() {
        when(provider.analyze(any())).thenReturn(result());
        doThrow(new IllegalStateException("database unavailable")).when(repository)
                .completeRerunSuccess(any(), anyLong(), any(), anyLong(), any(), anyLong(), any());

        assertThatThrownBy(this::rerun)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        verify(repository, never()).completeRerunFailure(
                any(), anyLong(), any(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void failedDatabaseWriteAfterProviderFailureAlsoPropagates() {
        when(provider.analyze(any())).thenThrow(new AiProviderException(
                AiProviderException.ErrorType.RATE_LIMITED, "rate limited"));
        doThrow(new IllegalStateException("failure update unavailable")).when(repository)
                .completeRerunFailure(any(), anyLong(), any(), anyLong(), any(), anyLong(), any());

        assertThatThrownBy(this::rerun)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failure update unavailable");
    }

    @Test
    void lostItemLeasePreventsLateProviderResultFromBeingSaved() {
        doThrow(new IllegalStateException("item lease lost")).when(repository)
                .renewLease(eq(claim), any(), any());
        when(provider.analyze(any())).thenAnswer(invocation -> {
            ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleAtFixedRate(heartbeat.capture(),
                    anyLong(), anyLong(), eq(TimeUnit.SECONDS));
            assertThatThrownBy(() -> heartbeat.getValue().run())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("item lease lost");
            return result();
        });

        assertThatThrownBy(this::rerun)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("item lease lost");

        verify(repository, never()).completeRerunSuccess(
                any(), anyLong(), any(), anyLong(), any(), anyLong(), any());
        verify(renewal).cancel(false);
    }

    @Test
    void unclaimedTargetDoesNotInvokeProvider() {
        when(repository.claimRerunItem(eq(OPERATION_ID), eq(EXECUTION_TOKEN),
                eq(ITEM_ID), eq("worker"), any(), any())).thenReturn(null);

        AiTaskJobService.RerunExecutionResult outcome = rerun();

        assertThat(outcome.isExecuted()).isFalse();
        assertThat(outcome.isSuccess()).isFalse();
        verify(provider, never()).analyze(any());
        verify(repository, never()).loadEvidence(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private AiTaskJobService.RerunExecutionResult rerun() {
        return service.executeRerunItem(OPERATION_ID, EXECUTION_TOKEN, ITEM_ID);
    }

    private static AiAnalysisResult result() {
        return new AiAnalysisResult(AiJudgement.CONFIRMED, ProblemLevel.HIGH,
                "CODE", "空指针", "创建订单时发生空指针", "调用栈定位到OrderService",
                "请求字段未校验", "订单创建失败", "增加非空校验", "重放失败请求",
                "未取得完整请求参数", null, 0.9D, true, null, "openai", "gpt-5.6-sol",
                "ai-issue-v2", "{\"result\":true}", "provider-request", "completed",
                Integer.valueOf(100), Integer.valueOf(20), Integer.valueOf(120));
    }

    private static AiTaskEvidenceContext evidence() {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        ActionableIssueSnapshot snapshot = new ActionableIssueSnapshot(
                14L, "prod", "demo", "order", "fingerprint", "fp-v2",
                "CODE", "HTTP", "IllegalStateException", "NullPointerException",
                "OrderService", "create", "create order failed", null,
                20L, now.minusHours(1), now, 31L,
                "create order failed", "at OrderService.create");
        return new AiTaskEvidenceContext(snapshot, Collections.emptyList());
    }
}
