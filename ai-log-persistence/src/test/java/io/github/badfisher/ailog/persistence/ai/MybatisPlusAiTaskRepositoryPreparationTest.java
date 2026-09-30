package io.github.badfisher.ailog.persistence.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper.GroupEventStats;
import io.github.badfisher.ailog.domain.ai.AiTaskEvidenceContext;
import java.util.concurrent.atomic.AtomicLong;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import io.github.badfisher.ailog.domain.ai.AiAnalysisResult;
import io.github.badfisher.ailog.domain.ai.AiCallFailure;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence;
import io.github.badfisher.ailog.domain.ai.AiJudgement;
import io.github.badfisher.ailog.domain.issue.ProblemLevel;
import io.github.badfisher.ailog.domain.ai.AiTaskClaim;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.domain.ai.SanitizedAiEvidence;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiCallAttemptEntity;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskEntity;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskItemEntity;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiCallAttemptMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskItemMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogManagementOperationMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper.AiTaskCandidate;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;

/** AI 主任务原子准备和并发结果落库行为测试。 */
class MybatisPlusAiTaskRepositoryPreparationTest {

    private AiLogAiTaskMapper taskMapper;
    private AiLogAiTaskItemMapper itemMapper;
    private AiLogAiCallAttemptMapper attemptMapper;
    private AiLogErrorEventMapper eventMapper;
    private AiLogManagementOperationMapper operations;
    private AiLogIssueGroupMapper groups;
    private AiLogIssueGroupGovernanceMapper governance;
    private MybatisPlusAiTaskRepository repository;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AiLogAiTaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, AiLogIssueGroupEntity.class);
        taskMapper = mock(AiLogAiTaskMapper.class);
        itemMapper = mock(AiLogAiTaskItemMapper.class);
        attemptMapper = mock(AiLogAiCallAttemptMapper.class);
        eventMapper = mock(AiLogErrorEventMapper.class);
        operations = mock(AiLogManagementOperationMapper.class);
        groups = mock(AiLogIssueGroupMapper.class);
        governance = mock(AiLogIssueGroupGovernanceMapper.class);
        when(governance.selectAutomaticGroupIds(any())).thenAnswer(call -> call.getArgument(0));
        when(groups.refreshAiState(any(), any())).thenReturn(1);
        when(taskMapper.selectAnalysisLogDate(3L)).thenReturn(LocalDate.of(2026, 9, 23));
        repository = new MybatisPlusAiTaskRepository(taskMapper, itemMapper,
                attemptMapper, operations,
                groups,
                eventMapper, new ObjectMapper(), taskPlan(), governance);
    }

    @Test
    void reservesGroupsAndPersistsRepresentativeBeforeCompletingPreparation() {
        prepareCandidates();
        when(groups.lockByIds(any())).thenReturn(Arrays.asList(eligibleGroup(101L), eligibleGroup(102L)));
        assertThat(repository.prepareTask(9L, LocalDateTime.now())).isTrue();
        ArgumentCaptor<AiLogAiTaskItemEntity> inserted = ArgumentCaptor.forClass(AiLogAiTaskItemEntity.class);
        verify(itemMapper, org.mockito.Mockito.times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(AiLogAiTaskItemEntity::getIssueGroupId)
                .containsExactly(101L, 102L);
        assertThat(inserted.getAllValues()).extracting(AiLogAiTaskItemEntity::getSampleEventId)
                .containsExactly(501L, 502L);
        assertThat(inserted.getAllValues()).extracting(AiLogAiTaskItemEntity::getOccurrenceCountSnapshot)
                .containsExactly(300L, 400L);
        assertThat(inserted.getAllValues()).extracting(AiLogAiTaskItemEntity::getFirstOccurredAtSnapshot)
                .containsOnly(LocalDateTime.of(2026, 9, 23, 0, 0));
        assertThat(inserted.getAllValues()).extracting(AiLogAiTaskItemEntity::getLastOccurredAtSnapshot)
                .containsOnly(LocalDateTime.of(2026, 9, 23, 23, 0));
        verify(eventMapper).selectGroupDailyEventStats(Arrays.asList(101L, 102L), LocalDate.of(2026, 9, 23));
        InOrder order = inOrder(groups, eventMapper, itemMapper);
        order.verify(groups).lockByIds(Arrays.asList(101L, 102L));
        order.verify(eventMapper).selectGroupDailyEvidenceReferences(Arrays.asList(101L, 102L), LocalDate.of(2026, 9, 23));
        order.verify(itemMapper).insert(any(AiLogAiTaskItemEntity.class));
        verify(eventMapper, org.mockito.Mockito.never()).selectGroupEvidenceEvents(any(), anyInt());
        verify(groups).reserveAi(101L, 1L);
        verify(groups).reserveAi(102L, 2L);
        verify(taskMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                (AiLogAiTaskEntity task) -> Boolean.TRUE.equals(task.getPreparationComplete())
                        && Integer.valueOf(2).equals(task.getTotalCount())));
    }

    @Test
    void rechecksLockedGroupToPreventDuplicateDispatch() {
        prepareCandidates();
        AiLogIssueGroupEntity completed = eligibleGroup(101L);
        completed.setCurrentAiItemId(700L);
        AiLogIssueGroupEntity reserved = eligibleGroup(102L);
        reserved.setActiveAiItemId(701L);
        when(groups.lockByIds(any())).thenReturn(Arrays.asList(completed, reserved));
        when(itemMapper.selectAutomaticGroupIds(any(), any())).thenReturn(Collections.singletonList(101L));
        repository.prepareTask(9L, LocalDateTime.now());
        verify(itemMapper, org.mockito.Mockito.never()).insert(any(AiLogAiTaskItemEntity.class));
        verify(groups, org.mockito.Mockito.never()).reserveAi(any(), any());
        verify(eventMapper, org.mockito.Mockito.never()).selectGroupDailyEvidenceReferences(any(), any());
        verify(eventMapper, org.mockito.Mockito.never()).selectGroupDailyEventStats(any(), any());
    }

    @Test
    void resolvedGroupsAreExcludedAfterLocking() {
        prepareCandidates();
        AiLogIssueGroupEntity reviewed = eligibleGroup(101L);
        when(governance.selectAutomaticGroupIds(any())).thenReturn(Collections.emptyList());
        AiLogIssueGroupEntity resolved = eligibleGroup(102L);

        when(groups.lockByIds(any())).thenReturn(Arrays.asList(reviewed, resolved));
        repository.prepareTask(9L, LocalDateTime.now());
        verify(itemMapper, org.mockito.Mockito.never()).insert(any(AiLogAiTaskItemEntity.class));
    }

    /** 无候选时直接完成准备，不锁 Group，也不发送空批量样本查询。 */
    @Test
    void completesEmptyPreparationWithoutEvidenceQuery() {
        prepareCandidates();
        when(eventMapper.selectAiTaskCandidates(3L, LocalDate.of(2026, 9, 23), 200)).thenReturn(Collections.emptyList());

        assertThat(repository.prepareTask(9L, LocalDateTime.now())).isFalse();

        verify(groups, org.mockito.Mockito.never()).lockByIds(any());
        verify(eventMapper, org.mockito.Mockito.never()).selectGroupDailyEvidenceReferences(any(), any());
        verify(taskMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                (AiLogAiTaskEntity task) -> Boolean.TRUE.equals(task.getPreparationComplete())
                        && Integer.valueOf(0).equals(task.getTotalCount())));
    }

    /** 候选证据意外丢失时回滚准备，不创建没有 sample_event_id 的 Item。 */
    @Test
    void missingRepresentativeEvidenceRejectsPreparation() {
        prepareCandidates();
        when(groups.lockByIds(any())).thenReturn(Arrays.asList(eligibleGroup(101L), eligibleGroup(102L)));
        when(eventMapper.selectGroupDailyEvidenceReferences(Arrays.asList(101L, 102L), LocalDate.of(2026, 9, 23)))
                .thenReturn(Arrays.asList(reference(101L, null), reference(102L, 502L)));

        assertThatThrownBy(() -> repository.prepareTask(9L, LocalDateTime.now()))
                .hasMessageContaining("lost representative evidence");
        verify(itemMapper, org.mockito.Mockito.never()).insert(any(AiLogAiTaskItemEntity.class));
        verify(taskMapper, org.mockito.Mockito.never()).updateById(any(AiLogAiTaskEntity.class));
    }

    /** 占位失败必须抛出异常，让外层事务回滚，不能完成主任务准备。 */
    @Test
    void propagatesReservationFailureBeforeCompletingPreparation() {
        prepareCandidates();
        when(groups.lockByIds(any())).thenReturn(Collections.singletonList(eligibleGroup(101L)));
        when(groups.reserveAi(101L, 1L)).thenReturn(0);

        assertThatThrownBy(() -> repository.prepareTask(9L, LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to reserve Group AI");

        verify(taskMapper, org.mockito.Mockito.never()).updateById(any(AiLogAiTaskEntity.class));
        verify(taskMapper, org.mockito.Mockito.never()).refreshSummary(any(), any());
    }

    private void prepareCandidates() {
        when(taskMapper.lockForSummaryUpdate(9L)).thenReturn(9L);
        when(taskMapper.selectById(9L)).thenReturn(pendingTask());
        when(eventMapper.selectAiTaskCandidates(3L, LocalDate.of(2026, 9, 23), 200))
                .thenReturn(Arrays.asList(candidate(101L), candidate(102L)));
        when(eventMapper.selectGroupDailyEvidenceReferences(any(), any()))
                .thenReturn(Arrays.asList(reference(101L, 501L), reference(102L, 502L)));
        Map<Long, GroupEventStats> statistics = new LinkedHashMap<Long, GroupEventStats>();
        for (long id = 101L; id <= 102L; id++) {
            GroupEventStats stats = new GroupEventStats();
            stats.setIssueGroupId(id);
            stats.setOccurrenceCount((id - 98L) * 100L);
            stats.setFirstSeenTime(LocalDateTime.of(2026, 9, 23, 0, 0));
            stats.setLastSeenTime(LocalDateTime.of(2026, 9, 23, 23, 0));
            statistics.put(id, stats);
        }
        when(eventMapper.selectGroupDailyEventStats(any(), any())).thenReturn(statistics);
        AtomicLong ids = new AtomicLong(1L);
        when(itemMapper.insert(any(AiLogAiTaskItemEntity.class))).thenAnswer(invocation -> {
            AiLogAiTaskItemEntity item = invocation.getArgument(0);
            item.setId(ids.getAndIncrement());
            return 1;
        });
        when(groups.reserveAi(any(), any())).thenReturn(1);
        when(taskMapper.updateById(any(AiLogAiTaskEntity.class))).thenReturn(1);
    }

    /** 执行时读取同批统计快照，全部异常字段取固定样本，不逐 Item 重查聚合。 */
    @Test
    void buildsEvidenceFromItemStatisticsAndTheSameRepresentativeEvent() {
        AiLogIssueGroupEntity issue = eligibleGroup(4L);
        issue.setRootCauseCategory("CODE");
        when(groups.selectById(4L)).thenReturn(issue);
        AiLogAiTaskItemEntity item = new AiLogAiTaskItemEntity();
        item.setId(6L);
        item.setAnalysisTaskId(3L);
        item.setSampleEventId(501L);
        item.setOccurrenceCountSnapshot(300L);
        item.setFirstOccurredAtSnapshot(LocalDateTime.of(2026, 9, 23, 0, 0));
        item.setLastOccurredAtSnapshot(LocalDateTime.of(2026, 9, 23, 23, 0));
        when(itemMapper.selectById(6L)).thenReturn(item);
        AiLogErrorEventEntity fixed = reference(4L, 501L);
        fixed.setLogDate(LocalDate.of(2026, 9, 23));
        fixed.setTriggerChannel("HTTP");
        fixed.setExceptionClass("OuterException");
        fixed.setRootCauseException("RootException");
        fixed.setBusinessClass("OrderService");
        fixed.setBusinessMethod("submit");
        fixed.setNormalizedMessage("fixed evidence");
        fixed.setMatchedRuleId(90L);
        when(eventMapper.selectById(501L)).thenReturn(fixed);
        when(eventMapper.selectGroupDailyEvidenceEvents(4L, LocalDate.of(2026, 9, 23), 1))
                .thenReturn(Collections.singletonList(reference(4L, 502L)));

        AiTaskEvidenceContext context = repository.loadEvidence(claim(), 1);

        assertThat(context.getSnapshot().getWindowEventCount()).isEqualTo(300L);
        assertThat(context.getSnapshot().getWindowFirstSeen())
                .isEqualTo(LocalDateTime.of(2026, 9, 23, 0, 0));
        assertThat(context.getSnapshot().getWindowLastSeen())
                .isEqualTo(LocalDateTime.of(2026, 9, 23, 23, 0));
        assertThat(context.getSnapshot().getExceptionClass()).isEqualTo("OuterException");
        assertThat(context.getSnapshot().getRootCauseException()).isEqualTo("RootException");
        assertThat(context.getSnapshot().getBusinessClass()).isEqualTo("OrderService");
        assertThat(context.getSnapshot().getBusinessMethod()).isEqualTo("submit");
        assertThat(context.getSnapshot().getNormalizedMessage()).isEqualTo("fixed evidence");
        verify(eventMapper, org.mockito.Mockito.never()).selectGroupDailyEventStats(any(), any());
    }

    private static AiLogErrorEventEntity reference(long groupId, Long eventId) {
        AiLogErrorEventEntity reference = new AiLogErrorEventEntity();
        reference.setIssueGroupId(groupId);
        reference.setId(eventId);
        return reference;
    }

    private static AiLogIssueGroupEntity eligibleGroup(long id) {
        AiLogIssueGroupEntity group = new AiLogIssueGroupEntity();
        group.setId(id);
        group.setAiStatus("WAITING");
        return group;
    }

    @Test
    void locksParentTaskBeforePersistingSuccessfulResult() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 10, 0);
        when(taskMapper.lockForSummaryUpdate(Long.valueOf(2L))).thenReturn(Long.valueOf(2L));
        when(attemptMapper.finishSuccess(any(), any(), anyLong(), any())).thenReturn(1);
        when(itemMapper.completeSuccess(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(1);
        when(taskMapper.refreshSummary(any(), any())).thenReturn(1);

        repository.completeSuccess(claim(), 7L, analysisResult(), 100L, now);

        InOrder order = inOrder(taskMapper, attemptMapper, itemMapper);
        order.verify(taskMapper).lockForSummaryUpdate(Long.valueOf(2L));
        order.verify(attemptMapper).finishSuccess(any(), any(), anyLong(), any());
        order.verify(itemMapper).completeSuccess(any(), any(), any(), anyLong(), any(), any());
        order.verify(taskMapper).refreshSummary(eq(Long.valueOf(2L)), any());
        InOrder projection = inOrder(groups, governance);
        projection.verify(groups).refreshAiState(4L, 6L);
        projection.verify(governance).fillMissingProblem(4L, "CODE", "LOW", now);
    }

    @Test
    void rerunSuccessFillsGovernanceOnlyAfterCurrentGroupProjection() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 24, 10, 0);
        when(operations.lockActiveRerunExecution(9L, "execution", now)).thenReturn(9L);
        when(attemptMapper.finishSuccess(any(), any(), anyLong(), any())).thenReturn(1);
        when(itemMapper.completeRerunSuccess(any(), any(), any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(1);
        repository.completeRerunSuccess(claim(), 9L, "execution", 7L, analysisResult(), 100L, now);
        InOrder order = inOrder(itemMapper, groups, governance);
        order.verify(itemMapper).completeRerunSuccess(any(), any(), any(), any(), any(), anyLong(), any(), any());
        order.verify(groups).refreshAiState(4L, 6L);
        order.verify(governance).fillMissingProblem(4L, "CODE", "LOW", now);
    }

    @Test
    void staleItemOrGroupCannotFillGovernance() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 24, 10, 0);
        when(taskMapper.lockForSummaryUpdate(2L)).thenReturn(2L);
        when(attemptMapper.finishSuccess(any(), any(), anyLong(), any())).thenReturn(1);
        when(itemMapper.completeSuccess(any(), any(), any(), anyLong(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> repository.completeSuccess(claim(), 7L, analysisResult(), 100L, now))
                .isInstanceOf(IllegalStateException.class);
        when(itemMapper.completeSuccess(any(), any(), any(), anyLong(), any(), any())).thenReturn(1);
        when(groups.refreshAiState(4L, 6L)).thenReturn(0);
        assertThatThrownBy(() -> repository.completeSuccess(claim(), 7L, analysisResult(), 100L, now))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(governance);
    }

    @Test
    void preservesRetryabilityAndPassesBackoffToTheSameItem() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 17, 10, 0);
        when(taskMapper.lockForSummaryUpdate(Long.valueOf(2L))).thenReturn(Long.valueOf(2L));
        when(attemptMapper.finishFailure(any(), any(), anyLong(), any())).thenReturn(1);
        when(itemMapper.completeFailure(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(1);
        AiCallFailure retryableFailure = new AiCallFailure(
                "RATE_LIMITED", "RATE_LIMITED", "rate limited", true,
                null, null, null, null, null, null, null);

        repository.completeFailure(claim(), 7L, retryableFailure, 100L, now);

        ArgumentCaptor<AiCallFailure> failureCaptor =
                ArgumentCaptor.forClass(AiCallFailure.class);
        verify(attemptMapper).finishFailure(eq(Long.valueOf(7L)), failureCaptor.capture(),
                eq(100L), eq(now));
        assertThat(failureCaptor.getValue().isRetryable()).isTrue();
        verify(itemMapper).completeFailure(eq(Long.valueOf(6L)), eq("claim-token"),
                eq(failureCaptor.getValue()), eq(100L), eq(now), eq(now.plusSeconds(1)));
        verify(taskMapper).refreshSummary(Long.valueOf(2L), now);
        org.mockito.Mockito.verifyNoInteractions(governance);
    }

    @Test
    void locksParentBeforeStartingAttemptToMatchRecoveryAndCompletionOrder() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 3, 10, 0);
        when(taskMapper.lockForSummaryUpdate(Long.valueOf(2L))).thenReturn(Long.valueOf(2L));
        when(itemMapper.markAttemptStarted(any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(1);
        doAnswer(invocation -> {
            AiLogAiCallAttemptEntity attempt = invocation.getArgument(0);
            attempt.setId(Long.valueOf(7L));
            return Integer.valueOf(1);
        }).when(attemptMapper).insert(any(AiLogAiCallAttemptEntity.class));
        AiIssueEvidence evidence = new AiIssueEvidence(4L, "test", "demo", "sample-service",
                "stable", "V1", "CODE", "HTTP", null, null, null, null, "message",
                null, 5L, null, null, "message", null, Collections.emptyList());

        long attemptId = repository.startAttempt(claim(), "request-1", "hash",
                new SanitizedAiEvidence(evidence), now);

        assertThat(attemptId).isEqualTo(7L);
        InOrder order = inOrder(taskMapper, itemMapper, attemptMapper);
        order.verify(taskMapper).lockForSummaryUpdate(Long.valueOf(2L));
        order.verify(itemMapper).markAttemptStarted(any(), any(), anyInt(), any(), any(), any());
        order.verify(attemptMapper).insert(any(AiLogAiCallAttemptEntity.class));
    }

    @Test
    void renewsLeaseOnlyForTheCurrentClaimToken() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 7, 10, 0);
        LocalDateTime leaseUntil = now.plusMinutes(3L);
        when(itemMapper.renewLease(Long.valueOf(6L), "claim-token", leaseUntil, now))
                .thenReturn(1);

        repository.renewLease(claim(), leaseUntil, now);

        verify(itemMapper).renewLease(Long.valueOf(6L), "claim-token", leaseUntil, now);
    }

    private static AiLogAiTaskEntity pendingTask() {
        AiLogAiTaskEntity task = new AiLogAiTaskEntity();
        task.setId(Long.valueOf(9L));
        task.setAnalysisTaskId(Long.valueOf(3L));
        task.setSelectionLimit(Integer.valueOf(200));
        task.setStatus("PENDING");
        return task;
    }

    private static AiTaskPlan taskPlan() {
        return new AiTaskPlan(true, "openai", "default", "gpt-5.6-sol",
                "ai-issue-v2", "sanitizer-v1", "{\"maxTokens\":2000}",
                200, 3, 1000L);
    }

    private static AiTaskClaim claim() {
        return new AiTaskClaim(6L, 2L, 4L, "openai", "gpt-5.6-sol",
                "ai-issue-v2", "sanitizer-v1", 0, 3, "claim-token");
    }

    private static AiAnalysisResult analysisResult() {
        return new AiAnalysisResult(AiJudgement.values()[0], ProblemLevel.values()[0],
                "CODE", "title", "summary", "basis", "root cause", "impact",
                "recommendation", "verification", "uncertainty", null, 0.9D, false, null,
                "openai", "gpt-5.6-sol", "ai-issue-v2", "{}", "request-1", "stop",
                Integer.valueOf(10), Integer.valueOf(20), Integer.valueOf(30));
    }

    private static AiTaskCandidate candidate(long issueGroupId) {
        AiTaskCandidate candidate = new AiTaskCandidate();
        candidate.setIssueGroupId(Long.valueOf(issueGroupId));
        return candidate;
    }
}
