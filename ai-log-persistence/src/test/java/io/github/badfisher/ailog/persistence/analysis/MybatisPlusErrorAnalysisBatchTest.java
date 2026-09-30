package io.github.badfisher.ailog.persistence.analysis;

import io.github.badfisher.ailog.persistence.workflow.MybatisPlusErrorAnalysisRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository.ClaimedFile;
import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository.AnalysisCounts;
import io.github.badfisher.ailog.domain.aggregation.AggregateBatch;
import io.github.badfisher.ailog.domain.aggregation.AggregateType;
import io.github.badfisher.ailog.domain.aggregation.AggregatedError;
import io.github.badfisher.ailog.domain.aggregation.ErrorSampleSnapshot;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.issue.TriggerChannel;
import io.github.badfisher.ailog.domain.log.LogLocationMode;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogAnalysisTaskEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupGovernanceEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogAnalysisTaskMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;
import io.github.badfisher.ailog.persistence.cleanup.entity.AiLogFileCleanupEntity;
import io.github.badfisher.ailog.persistence.cleanup.mapper.AiLogFileCleanupMapper;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogFileRecordEntity;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogFileRecordMapper;

/** 文件级聚合持久化测试：真实次数、Issue复用、分类事实、重跑与失败保留。 */
class MybatisPlusErrorAnalysisBatchTest {

    private AiLogIssueGroupMapper issueMapper;
    private AiLogErrorEventMapper eventMapper;
    private AiLogAnalysisTaskMapper taskMapper;
    private AiLogFileRecordMapper fileMapper;
    private AiLogFileCleanupMapper cleanupMapper;
    private AiLogIssueGroupGovernanceMapper governanceMapper;
    private MybatisPlusErrorAnalysisRepository repository;

    @BeforeEach
    void setUp() {
        initializeTableMetadata(AiLogAnalysisTaskEntity.class);
        initializeTableMetadata(AiLogIssueGroupEntity.class);
        initializeTableMetadata(AiLogFileCleanupEntity.class);
        initializeTableMetadata(AiLogFileRecordEntity.class);
        issueMapper = mock(AiLogIssueGroupMapper.class);
        eventMapper = mock(AiLogErrorEventMapper.class);
        taskMapper = mock(AiLogAnalysisTaskMapper.class);
        fileMapper = mock(AiLogFileRecordMapper.class);
        cleanupMapper = mock(AiLogFileCleanupMapper.class);
        when(taskMapper.update(any(), any(Wrapper.class))).thenReturn(Integer.valueOf(1));
        governanceMapper = mock(AiLogIssueGroupGovernanceMapper.class);
        repository = new MybatisPlusErrorAnalysisRepository(fileMapper, taskMapper,
                issueMapper, eventMapper, cleanupMapper, governanceMapper);
    }

    /** 为纯 Mock 测试初始化 MyBatis-Plus Lambda 字段缓存。 */
    private static void initializeTableMetadata(Class<?> entityType) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, entityType);
    }

    @Test
    void refusesSuccessfulCompletionWhenDatabaseOccurrenceCountDoesNotMatch() {
        when(eventMapper.sumOccurrencesByAnalysisTask(Long.valueOf(1L))).thenReturn(4L);
        AnalysisCounts counts = new AnalysisCounts(6L, 6L, 0L, 0L, 0L, 1L, 5L);

        assertThatThrownBy(() -> repository.complete(claimedFile(), counts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解析落库次数校验失败");
        verify(taskMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void completesWhenDatabaseCountMatchesPersistedAfterSuppressionAndUnknownDiscard() {
        when(eventMapper.sumOccurrencesByAnalysisTask(Long.valueOf(1L))).thenReturn(7L);
        when(eventMapper.countDistinctIssues(Long.valueOf(1L))).thenReturn(2L);
        AnalysisCounts counts = new AnalysisCounts(12L, 7L, 3L, 2L, 2L, 1L, 7L);

        repository.complete(claimedFile(), counts);

        ArgumentCaptor<AiLogAnalysisTaskEntity> task =
                ArgumentCaptor.forClass(AiLogAnalysisTaskEntity.class);
        verify(taskMapper).update(task.capture(), any(Wrapper.class));
        assertThat(task.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(task.getValue().getSuppressedErrorCount()).isEqualTo(Long.valueOf(2L));
        assertThat(task.getValue().getDiscardedUnknownCount()).isEqualTo(Long.valueOf(1L));
        assertThat(task.getValue().getPersistedErrorCount()).isEqualTo(Long.valueOf(7L));
    }

    @Test
    void persistsAggregateDeltaOnlyInEvent() {
        when(issueMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(existingIssue()));
        AggregateBatch batch = aggregateBatch(15000L);

        long persisted = repository.persistAggregates(claimedFile(), batch);

        assertThat(persisted).isEqualTo(15000L);
        ArgumentCaptor<List<AiLogErrorEventEntity>> eventsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(eventMapper).upsertAggregates(eventsCaptor.capture());
        AiLogErrorEventEntity event = eventsCaptor.getValue().get(0);
        assertThat(event.getOccurrenceCount()).isEqualTo(Long.valueOf(15000L));
        assertThat(event.getSampleContent()).isEqualTo("representative sample");
        assertThat(event.getStableFingerprint()).isEqualTo("stable");
        assertThat(event.getAggregateKey()).isEqualTo("aggregate-key");
        verify(issueMapper).selectList(any(Wrapper.class));
        org.mockito.Mockito.verifyNoMoreInteractions(issueMapper);
        verify(eventMapper, never()).insert(any(AiLogErrorEventEntity.class));
        verify(taskMapper).update(isNull(), any(Wrapper.class));
    }

    /** log_date 必须来自 ClaimedFile 携带的 AnalysisTask 业务日期，样本评分随聚合增量透传。 */
    @Test
    void persistsLogDateFromAnalysisTaskAndSampleQualityScore() {
        when(issueMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(existingIssue()));

        repository.persistAggregates(claimedFile(), aggregateBatch(3L));

        ArgumentCaptor<List<AiLogErrorEventEntity>> eventsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(eventMapper).upsertAggregates(eventsCaptor.capture());
        AiLogErrorEventEntity event = eventsCaptor.getValue().get(0);
        assertThat(event.getLogDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(event.getSampleQualityScore()).isEqualTo(Integer.valueOf(7));
        assertThat(event.getReasonCode()).isNull();
    }

    /** 直接模式同样携带业务日期；expected BUSINESS 事件透传 reasonCode。 */
    @Test
    void persistsExpectedBusinessReasonCodeWithLogDate() {
        when(issueMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());

        repository.persistAggregates(directClaimedFile(),
                aggregateBatch(9L, RootCauseCategory.BUSINESS, true));

        ArgumentCaptor<List<AiLogErrorEventEntity>> eventsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(eventMapper).upsertAggregates(eventsCaptor.capture());
        AiLogErrorEventEntity event = eventsCaptor.getValue().get(0);
        assertThat(event.getLogDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(event.getReasonCode()).isEqualTo("EXPECTED_BUSINESS");
        assertThat(event.getAggregateType()).isEqualTo("BUSINESS");
    }

    @Test
    void removesPreviousTaskEventsWithoutTouchingGroupsBeforeRetry() {
        repository.prepareForAnalysis(claimedFile());

        verify(eventMapper).delete(any(Wrapper.class));
        org.mockito.Mockito.verifyNoInteractions(issueMapper);
    }

    @Test
    void directAggregatePersistsNullableFileReferencesWithoutLifecycleUpdates() {
        when(issueMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        doAnswer(invocation -> {
            AiLogIssueGroupEntity issue = invocation.getArgument(0);
            issue.setId(Long.valueOf(88L));
            return Integer.valueOf(1);
        }).when(issueMapper).insert(any(AiLogIssueGroupEntity.class));

        long persisted = repository.persistAggregates(directClaimedFile(), aggregateBatch(7L));

        assertThat(persisted).isEqualTo(7L);
        ArgumentCaptor<AiLogIssueGroupEntity> issueCaptor =
                ArgumentCaptor.forClass(AiLogIssueGroupEntity.class);
        verify(issueMapper).insert(issueCaptor.capture());
        ArgumentCaptor<List<AiLogErrorEventEntity>> eventsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(eventMapper).upsertAggregates(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue().get(0).getFileRecordId()).isNull();
        verify(fileMapper, never()).update(any(), any(Wrapper.class));
        verify(cleanupMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void directCompletionDoesNotUpdateFileOrActivateCleanup() {
        when(eventMapper.sumOccurrencesByAnalysisTask(Long.valueOf(1L))).thenReturn(0L);
        when(eventMapper.countDistinctIssues(Long.valueOf(1L))).thenReturn(0L);

        repository.complete(directClaimedFile(), new AnalysisCounts(0L, 0L, 0L, 0L, 0L, 0L, 0L));

        verify(taskMapper).update(any(AiLogAnalysisTaskEntity.class), any(Wrapper.class));
        verify(fileMapper, never()).update(any(), any(Wrapper.class));
        verify(cleanupMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void expectedBusinessPersistsEventWithoutCreatingIssueGroup() {
        AggregateBatch batch = aggregateBatch(10000L, RootCauseCategory.BUSINESS, true);

        long persisted = repository.persistAggregates(claimedFile(), batch);

        assertThat(persisted).isEqualTo(10000L);
        ArgumentCaptor<List<AiLogErrorEventEntity>> eventsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(eventMapper).upsertAggregates(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue()).hasSize(1);
        assertThat(eventsCaptor.getValue().get(0).getIssueGroupId()).isNull();
        verify(issueMapper, never()).insert(any(AiLogIssueGroupEntity.class));
    }

    @Test
    void failurePreservesFlushedAggregates() {
        repository.fail(claimedFile(), "parser failed after flush");

        verify(eventMapper, never()).delete(any(Wrapper.class));
        verify(taskMapper).update(any(AiLogAnalysisTaskEntity.class), any(Wrapper.class));
    }

    /** Empty flushes must not create rows or refresh the task heartbeat. */
    @Test
    void emptyAggregateBatchDoesNotWriteAnything() {
        assertThat(repository.persistAggregates(claimedFile(), null)).isZero();
        assertThat(repository.persistAggregates(claimedFile(),
                new AggregateBatch(Collections.<AggregatedError>emptyList()))).isZero();

        verify(issueMapper, never()).selectList(any(Wrapper.class));
        verify(eventMapper, never()).upsertAggregates(any());
        verify(taskMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void createsPermanentIssueAndStoresOccurrenceOnlyInEvent() {
        when(issueMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        doAnswer(invocation -> {
            AiLogIssueGroupEntity issue = invocation.getArgument(0);
            issue.setId(Long.valueOf(88L));
            return Integer.valueOf(1);
        }).when(issueMapper).insert(any(AiLogIssueGroupEntity.class));

        long persisted = repository.persistAggregates(claimedFile(), aggregateBatch(7L));

        assertThat(persisted).isEqualTo(7L);
        ArgumentCaptor<AiLogIssueGroupEntity> issueCaptor =
                ArgumentCaptor.forClass(AiLogIssueGroupEntity.class);
        verify(issueMapper).insert(issueCaptor.capture());
        assertThat(issueCaptor.getValue().getRootCauseCategory()).isEqualTo("CODE");
        assertThat(issueCaptor.getValue().getStableFingerprint()).isEqualTo("stable");
        assertThat(issueCaptor.getValue().getAiStatus()).isEqualTo("WAITING");
        ArgumentCaptor<AiLogIssueGroupGovernanceEntity> governanceCaptor =
                ArgumentCaptor.forClass(AiLogIssueGroupGovernanceEntity.class);
        verify(governanceMapper).insert(governanceCaptor.capture());
        assertThat(governanceCaptor.getValue().getIssueGroupId()).isEqualTo(88L);
        assertThat(governanceCaptor.getValue().getReviewStatus()).isEqualTo("PENDING");
        assertThat(governanceCaptor.getValue().getProcessStatus()).isEqualTo("PENDING");
        assertThat(governanceCaptor.getValue().getVersion()).isZero();
        ArgumentCaptor<List<AiLogErrorEventEntity>> eventsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(eventMapper).upsertAggregates(eventsCaptor.capture());
        AiLogErrorEventEntity event = eventsCaptor.getValue().get(0);
        assertThat(event.getIssueGroupId()).isEqualTo(88L);
    }

    @Test
    void existingIssueIsReusedWithoutReinserting() {
        when(issueMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(existingIssue()));

        long persisted = repository.persistAggregates(claimedFile(), aggregateBatch(5L));

        assertThat(persisted).isEqualTo(5L);
        verify(issueMapper, never()).insert(any(AiLogIssueGroupEntity.class));
        ArgumentCaptor<List<AiLogErrorEventEntity>> eventsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(eventMapper).upsertAggregates(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue().get(0).getIssueGroupId()).isEqualTo(88L);
    }

    /** 同批重复指纹只进行一次 IN 查询、一次创建，全部 Event 复用该永久身份。 */
    @Test
    void deduplicatesFingerprintsBeforeBatchLookupAndCreation() {
        when(issueMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        doAnswer(invocation -> {
            AiLogIssueGroupEntity issue = invocation.getArgument(0);
            issue.setId(88L);
            return 1;
        }).when(issueMapper).insert(any(AiLogIssueGroupEntity.class));
        AggregatedError error = aggregateBatch(5L).getErrors().get(0);

        repository.persistAggregates(claimedFile(),
                new AggregateBatch(java.util.Arrays.asList(error, error)));

        ArgumentCaptor<Wrapper> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(issueMapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("stable_fingerprint IN");
        verify(issueMapper).insert(any(AiLogIssueGroupEntity.class));
        org.mockito.Mockito.verifyNoMoreInteractions(issueMapper);
        ArgumentCaptor<List<AiLogErrorEventEntity>> written = ArgumentCaptor.forClass(List.class);
        verify(eventMapper).upsertAggregates(written.capture());
        assertThat(written.getValue()).extracting(AiLogErrorEventEntity::getIssueGroupId)
                .containsExactly(88L, 88L);
    }

    @Test
    void concurrentIssueCreationReusesTheWinningRow() {
        when(issueMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        doThrow(new DuplicateKeyException("concurrent issue creation")).when(issueMapper)
                .insert(any(AiLogIssueGroupEntity.class));
        when(issueMapper.lockByIdentity("prod", "demo", "sample-service", "stable", "fp-v2"))
                .thenReturn(existingIssue());

        long persisted = repository.persistAggregates(claimedFile(), aggregateBatch(5L));

        assertThat(persisted).isEqualTo(5L);
        verify(issueMapper).lockByIdentity("prod", "demo", "sample-service", "stable", "fp-v2");
        ArgumentCaptor<List<AiLogErrorEventEntity>> eventsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(eventMapper).upsertAggregates(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue().get(0).getIssueGroupId()).isEqualTo(88L);
    }

    /** Event decisions remain evidence; an existing Issue is not reclassified by the increment. */
    @Test
    void currentEventDecisionDoesNotOverwriteExistingIssueClassification() {
        AiLogIssueGroupEntity existing = existingIssue();
        existing.setRootCauseCategory("BUSINESS");
        when(issueMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(existing));

        repository.persistAggregates(claimedFile(), aggregateBatch(5L));

        ArgumentCaptor<List<AiLogErrorEventEntity>> eventsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(eventMapper).upsertAggregates(eventsCaptor.capture());
        AiLogErrorEventEntity event = eventsCaptor.getValue().get(0);
        assertThat(event.getRootCauseCategory()).isEqualTo("CODE");
        assertThat(event.getMatchedRuleId()).isEqualTo(42L);
        assertThat(event.getExpected()).isFalse();
        assertThat(existing.getRootCauseCategory()).isEqualTo("BUSINESS");
        verify(issueMapper, never()).updateById(any(AiLogIssueGroupEntity.class));
    }

    @Test
    void rejectsConcurrentIssueCreationWhenWinningRowCannotBeLoaded() {
        when(issueMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        doThrow(new DuplicateKeyException("concurrent issue creation")).when(issueMapper)
                .insert(any(AiLogIssueGroupEntity.class));
        when(issueMapper.lockByIdentity("prod", "demo", "sample-service", "stable", "fp-v2"))
                .thenReturn(null);

        assertThatThrownBy(() -> repository.persistAggregates(claimedFile(), aggregateBatch(5L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Concurrent Issue Group could not be loaded");

        verify(eventMapper, never()).upsertAggregates(any());
    }

    @Test
    void rejectsAggregateCommitAfterParsingTaskHasBecomeTerminal() {
        when(issueMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(existingIssue()));
        when(taskMapper.update(isNull(), any(Wrapper.class))).thenReturn(Integer.valueOf(0));

        assertThatThrownBy(() -> repository.persistAggregates(claimedFile(), aggregateBatch(5L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer parsing");
    }

    @Test
    void doesNotDeletePreviousAggregatesAfterParsingTaskHasBecomeTerminal() {
        when(taskMapper.update(isNull(), any(Wrapper.class))).thenReturn(Integer.valueOf(0));

        assertThatThrownBy(() -> repository.prepareForAnalysis(claimedFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer parsing");

        verify(eventMapper, never()).delete(any(Wrapper.class));
    }

    private static AiLogIssueGroupEntity existingIssue() {
        AiLogIssueGroupEntity issue = new AiLogIssueGroupEntity();
        issue.setId(Long.valueOf(88L));
        issue.setStableFingerprint("stable");
        issue.setFingerprintVersion("fp-v2");
        return issue;
    }

    private static AggregateBatch aggregateBatch(long occurrenceCount) {
        return aggregateBatch(occurrenceCount, RootCauseCategory.CODE, false);
    }

    private static AggregateBatch aggregateBatch(long occurrenceCount,
            RootCauseCategory category, boolean expected) {
        Instant firstSeen = Instant.parse("2026-08-01T04:00:00Z");
        Instant lastSeen = Instant.parse("2026-08-01T04:10:00Z");
        ErrorSampleSnapshot sample = new ErrorSampleSnapshot(firstSeen,
                LogLocationMode.PLAIN_BYTE_OFFSET, 10L, 12L, 100L, 300L,
                "main", "tid", "trace", "request", "com.badfisher.Service", "run",
                Integer.valueOf(10), TriggerChannel.HTTP, "java.lang.RuntimeException",
                "error", "java.lang.IllegalStateException", "root error",
                "com.badfisher.Service", "run", Integer.valueOf(10), "normalized",
                "stack", "strict", "stable", "representative sample", false,
                false, "STRICT_ERROR", Long.valueOf(42L));
        AggregateType aggregateType = expected ? AggregateType.BUSINESS : AggregateType.ISSUE;
        AggregatedError error = new AggregatedError(aggregateType,
                "aggregate-key", category, expected, !expected,
                expected ? "EXPECTED_BUSINESS" : null,
                "stable", "fp-v2", occurrenceCount, firstSeen,
                lastSeen, Long.valueOf(42L), 7, sample);
        return new AggregateBatch(Collections.singletonList(error));
    }

    private static ClaimedFile claimedFile() {
        return new ClaimedFile(1L, 2L, 3L, 4L, "prod", "demo", "sample-service",
                LocalDate.of(2026, 8, 1), "E:/logs/error.log", "fp-v2");
    }

    private static ClaimedFile directClaimedFile() {
        return new ClaimedFile(1L, null, null, null, "prod", "demo", "sample-service",
                LocalDate.of(2026, 8, 1), "E:/logs/error.log", "fp-v2");
    }
}
