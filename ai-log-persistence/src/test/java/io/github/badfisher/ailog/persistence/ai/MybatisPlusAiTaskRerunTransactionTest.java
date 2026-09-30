package io.github.badfisher.ailog.persistence.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.badfisher.ailog.domain.ai.AiAnalysisResult;
import io.github.badfisher.ailog.domain.ai.AiCallFailure;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence;
import io.github.badfisher.ailog.domain.ai.AiJudgement;
import io.github.badfisher.ailog.domain.issue.ProblemLevel;
import io.github.badfisher.ailog.domain.ai.AiTaskClaim;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.domain.ai.AiTaskRepository;
import io.github.badfisher.ailog.domain.ai.SanitizedAiEvidence;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiCallAttemptEntity;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiCallAttemptMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskItemMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogManagementOperationMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;

/** AI 显式重跑仓储的真实 Spring 事务代理边界测试。 */
class MybatisPlusAiTaskRerunTransactionTest {

    private static final long OPERATION_ID = 41L;
    private static final long ATTEMPT_ID = 71L;
    private static final String OPERATION_TOKEN = "operation-token";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 10, 0);
    private static final List<String> EVENTS = new CopyOnWriteArrayList<String>();

    @BeforeEach
    void clearEvents() {
        EVENTS.clear();
    }

    @Test
    void startRerunAttemptCommitsAndKeepsHistoricalAttemptSequenceBeyondMaximum() {
        new ApplicationContextRunner()
                .withUserConfiguration(TransactionConfiguration.class)
                .run(context -> {
                    AiLogAiTaskItemMapper itemMapper = context.getBean(
                            AiLogAiTaskItemMapper.class);
                    AiLogAiCallAttemptMapper attemptMapper = context.getBean(
                            AiLogAiCallAttemptMapper.class);
                    prepareOperationLock(context.getBean(
                            AiLogManagementOperationMapper.class));
                    when(itemMapper.markRerunAttemptStarted(any(), any(), any(), any(),
                            anyInt(), any(), any(), any()))
                            .thenAnswer(invocation -> transactionalResult(
                                    "item-attempt-cas", Integer.valueOf(1)));
                    when(attemptMapper.insert(any(AiLogAiCallAttemptEntity.class)))
                            .thenAnswer(invocation -> {
                                requireActiveTransaction();
                                EVENTS.add("attempt-insert");
                                AiLogAiCallAttemptEntity attempt = invocation.getArgument(0);
                                attempt.setId(Long.valueOf(ATTEMPT_ID));
                                return Integer.valueOf(1);
                            });

                    AiTaskRepository repository = context.getBean(AiTaskRepository.class);
                    long attemptId = repository.startRerunAttempt(historicalClaim(),
                            OPERATION_ID, OPERATION_TOKEN, "request-8", "evidence-hash",
                            sanitizedEvidence(), NOW);

                    assertThat(AopUtils.isAopProxy(repository)).isTrue();
                    assertThat(attemptId).isEqualTo(ATTEMPT_ID);
                    assertThat(EVENTS).containsExactly("transaction-begin", "operation-lock",
                            "item-attempt-cas", "attempt-insert", "transaction-commit");
                    ArgumentCaptor<AiLogAiCallAttemptEntity> attemptCaptor =
                            ArgumentCaptor.forClass(AiLogAiCallAttemptEntity.class);
                    verify(attemptMapper).insert(attemptCaptor.capture());
                    AiLogAiCallAttemptEntity attempt = attemptCaptor.getValue();
                    assertThat(attempt.getAiTaskItemId()).isEqualTo(Long.valueOf(6L));
                    assertThat(attempt.getRerunOperationId())
                            .isEqualTo(Long.valueOf(OPERATION_ID));
                    assertThat(attempt.getAttemptNo()).isEqualTo(Integer.valueOf(8));
                    assertThat(attempt.getRequestMode()).isEqualTo("MANUAL_RERUN");
                });
    }

    @Test
    void startRerunAttemptRollsBackWhenAttemptInsertFailsAfterItemCas() {
        new ApplicationContextRunner()
                .withUserConfiguration(TransactionConfiguration.class)
                .run(context -> {
                    AiLogAiTaskItemMapper itemMapper = context.getBean(
                            AiLogAiTaskItemMapper.class);
                    AiLogAiCallAttemptMapper attemptMapper = context.getBean(
                            AiLogAiCallAttemptMapper.class);
                    prepareOperationLock(context.getBean(
                            AiLogManagementOperationMapper.class));
                    when(itemMapper.markRerunAttemptStarted(any(), any(), any(), any(),
                            anyInt(), any(), any(), any()))
                            .thenAnswer(invocation -> transactionalResult(
                                    "item-attempt-cas", Integer.valueOf(1)));
                    when(attemptMapper.insert(any(AiLogAiCallAttemptEntity.class)))
                            .thenAnswer(invocation -> transactionalResult(
                                    "attempt-insert", Integer.valueOf(0)));

                    AiTaskRepository repository = context.getBean(AiTaskRepository.class);
                    assertThatThrownBy(() -> repository.startRerunAttempt(historicalClaim(),
                            OPERATION_ID, OPERATION_TOKEN, "request-8", "evidence-hash",
                            sanitizedEvidence(), NOW))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("rerun attempt insert failed");

                    assertThat(EVENTS).containsExactly("transaction-begin", "operation-lock",
                            "item-attempt-cas", "attempt-insert", "transaction-rollback");
                });
    }

    @Test
    void completeRerunSuccessCommitsWithoutRefreshingParentTask() {
        new ApplicationContextRunner()
                .withUserConfiguration(TransactionConfiguration.class)
                .run(context -> {
                    AiLogAiTaskMapper taskMapper = context.getBean(AiLogAiTaskMapper.class);
                    AiLogAiTaskItemMapper itemMapper = context.getBean(
                            AiLogAiTaskItemMapper.class);
                    AiLogAiCallAttemptMapper attemptMapper = context.getBean(
                            AiLogAiCallAttemptMapper.class);
                    prepareOperationLock(context.getBean(
                            AiLogManagementOperationMapper.class));
                    AiAnalysisResult result = analysisResult();
                    when(attemptMapper.finishSuccess(Long.valueOf(ATTEMPT_ID),
                            result, 120L, NOW))
                            .thenAnswer(invocation -> transactionalResult(
                                    "attempt-success", Integer.valueOf(1)));
                    when(itemMapper.completeRerunSuccess(Long.valueOf(OPERATION_ID),
                            OPERATION_TOKEN, Long.valueOf(6L), "claim-token",
                            result, 120L, resultHash(), NOW))
                            .thenAnswer(invocation -> transactionalResult(
                                    "item-success-cas", Integer.valueOf(1)));

                    AiTaskRepository repository = context.getBean(AiTaskRepository.class);
                    repository.completeRerunSuccess(historicalClaim(), OPERATION_ID,
                            OPERATION_TOKEN, ATTEMPT_ID, result, 120L, NOW);

                    assertThat(AopUtils.isAopProxy(repository)).isTrue();
                    assertThat(EVENTS).containsExactly("transaction-begin", "operation-lock",
                            "attempt-success", "item-success-cas", "transaction-commit");
                    verify(taskMapper, never()).refreshSummary(any(), any());
                });
    }

    @Test
    void completeRerunSuccessRollsBackAttemptWhenItemCasRejectsStaleClaim() {
        new ApplicationContextRunner()
                .withUserConfiguration(TransactionConfiguration.class)
                .run(context -> {
                    AiLogAiTaskItemMapper itemMapper = context.getBean(
                            AiLogAiTaskItemMapper.class);
                    AiLogAiCallAttemptMapper attemptMapper = context.getBean(
                            AiLogAiCallAttemptMapper.class);
                    prepareOperationLock(context.getBean(
                            AiLogManagementOperationMapper.class));
                    when(attemptMapper.finishSuccess(any(), any(), anyLong(), any()))
                            .thenAnswer(invocation -> transactionalResult(
                                    "attempt-success", Integer.valueOf(1)));
                    when(itemMapper.completeRerunSuccess(any(), any(), any(), any(), any(),
                            anyLong(), any(), any()))
                            .thenAnswer(invocation -> transactionalResult(
                                    "item-success-cas", Integer.valueOf(0)));

                    AiTaskRepository repository = context.getBean(AiTaskRepository.class);
                    assertThatThrownBy(() -> repository.completeRerunSuccess(historicalClaim(),
                            OPERATION_ID, OPERATION_TOKEN, ATTEMPT_ID,
                            analysisResult(), 120L, NOW))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("claim is stale");

                    assertThat(EVENTS).containsExactly("transaction-begin", "operation-lock",
                            "attempt-success", "item-success-cas", "transaction-rollback");
                });
    }

    @Test
    void completeRerunFailureCommitsTerminalFailureWithoutRefreshingParentTask() {
        new ApplicationContextRunner()
                .withUserConfiguration(TransactionConfiguration.class)
                .run(context -> {
                    AiLogAiTaskMapper taskMapper = context.getBean(AiLogAiTaskMapper.class);
                    AiLogAiTaskItemMapper itemMapper = context.getBean(
                            AiLogAiTaskItemMapper.class);
                    AiLogAiCallAttemptMapper attemptMapper = context.getBean(
                            AiLogAiCallAttemptMapper.class);
                    prepareOperationLock(context.getBean(
                            AiLogManagementOperationMapper.class));
                    when(attemptMapper.finishFailure(any(), any(), anyLong(), any()))
                            .thenAnswer(invocation -> transactionalResult(
                                    "attempt-failure", Integer.valueOf(1)));
                    when(itemMapper.completeRerunFailure(any(), any(), any(), any(), any(),
                            anyLong(), any()))
                            .thenAnswer(invocation -> transactionalResult(
                                    "item-failure-cas", Integer.valueOf(1)));

                    AiTaskRepository repository = context.getBean(AiTaskRepository.class);
                    repository.completeRerunFailure(historicalClaim(), OPERATION_ID,
                            OPERATION_TOKEN, ATTEMPT_ID, retryableFailure(), 180L, NOW);

                    assertThat(AopUtils.isAopProxy(repository)).isTrue();
                    assertThat(EVENTS).containsExactly("transaction-begin", "operation-lock",
                            "attempt-failure", "item-failure-cas", "transaction-commit");
                    ArgumentCaptor<AiCallFailure> failureCaptor =
                            ArgumentCaptor.forClass(AiCallFailure.class);
                    verify(itemMapper).completeRerunFailure(
                            org.mockito.ArgumentMatchers.eq(Long.valueOf(OPERATION_ID)),
                            org.mockito.ArgumentMatchers.eq(OPERATION_TOKEN),
                            org.mockito.ArgumentMatchers.eq(Long.valueOf(6L)),
                            org.mockito.ArgumentMatchers.eq("claim-token"),
                            failureCaptor.capture(), org.mockito.ArgumentMatchers.eq(180L),
                            org.mockito.ArgumentMatchers.eq(NOW));
                    assertThat(failureCaptor.getValue().isRetryable()).isFalse();
                    verify(taskMapper, never()).refreshSummary(any(), any());
                });
    }

    @Test
    void failRerunBeforeCallCommitsWithoutRefreshingParentTask() {
        new ApplicationContextRunner()
                .withUserConfiguration(TransactionConfiguration.class)
                .run(context -> {
                    AiLogAiTaskMapper taskMapper = context.getBean(AiLogAiTaskMapper.class);
                    AiLogAiTaskItemMapper itemMapper = context.getBean(
                            AiLogAiTaskItemMapper.class);
                    prepareOperationLock(context.getBean(
                            AiLogManagementOperationMapper.class));
                    when(itemMapper.failRerunBeforeCall(Long.valueOf(OPERATION_ID),
                            OPERATION_TOKEN, Long.valueOf(6L), "claim-token",
                            "EVIDENCE_INVALID", "evidence unavailable", NOW))
                            .thenAnswer(invocation -> transactionalResult(
                                    "item-fail-before-call", Integer.valueOf(1)));

                    AiTaskRepository repository = context.getBean(AiTaskRepository.class);
                    repository.failRerunBeforeCall(historicalClaim(), OPERATION_ID,
                            OPERATION_TOKEN, "EVIDENCE_INVALID", "evidence unavailable", NOW);

                    assertThat(AopUtils.isAopProxy(repository)).isTrue();
                    assertThat(EVENTS).containsExactly("transaction-begin", "operation-lock",
                            "item-fail-before-call", "transaction-commit");
                    verify(taskMapper, never()).refreshSummary(any(), any());
                });
    }

    @Test
    void recoverExpiredRerunItemsCommitsAttemptAndItemRecoveryTogether() {
        new ApplicationContextRunner()
                .withUserConfiguration(TransactionConfiguration.class)
                .run(context -> {
                    AiLogAiTaskMapper taskMapper = context.getBean(AiLogAiTaskMapper.class);
                    AiLogAiTaskItemMapper itemMapper = context.getBean(
                            AiLogAiTaskItemMapper.class);
                    AiLogAiCallAttemptMapper attemptMapper = context.getBean(
                            AiLogAiCallAttemptMapper.class);
                    prepareOperationLock(context.getBean(
                            AiLogManagementOperationMapper.class));
                    when(attemptMapper.failExpiredRerunAttempts(
                            Long.valueOf(OPERATION_ID), NOW))
                            .thenAnswer(invocation -> transactionalResult(
                                    "attempt-recovery", Integer.valueOf(1)));
                    when(itemMapper.recoverExpiredRerunItems(
                            Long.valueOf(OPERATION_ID), NOW))
                            .thenAnswer(invocation -> transactionalResult(
                                    "item-recovery", Integer.valueOf(2)));

                    AiTaskRepository repository = context.getBean(AiTaskRepository.class);
                    int recovered = repository.recoverExpiredRerunItems(OPERATION_ID,
                            OPERATION_TOKEN, NOW);

                    assertThat(AopUtils.isAopProxy(repository)).isTrue();
                    assertThat(recovered).isEqualTo(2);
                    assertThat(EVENTS).containsExactly("transaction-begin", "operation-lock",
                            "attempt-recovery", "item-recovery", "transaction-commit");
                    verify(taskMapper, never()).refreshSummary(any(), any());
                });
    }

    private static <T> T transactionalResult(String event, T result) {
        requireActiveTransaction();
        EVENTS.add(event);
        return result;
    }

    private static void prepareOperationLock(AiLogManagementOperationMapper operationMapper) {
        when(operationMapper.lockActiveRerunExecution(
                Long.valueOf(OPERATION_ID), OPERATION_TOKEN, NOW))
                .thenAnswer(invocation -> transactionalResult(
                        "operation-lock", Long.valueOf(OPERATION_ID)));
    }

    private static void requireActiveTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    }

    private static AiTaskClaim historicalClaim() {
        return new AiTaskClaim(6L, 2L, 4L, "openai", "gpt-5.6-sol",
                "ai-issue-v2", "sanitizer-v1", 7, 3, "claim-token");
    }

    private static SanitizedAiEvidence sanitizedEvidence() {
        AiIssueEvidence evidence = new AiIssueEvidence(4L, "prod", "demo", "order",
                "stable", "V1", "CODE", "HTTP", null, null, null, null,
                "message", null, 5L, null, null, "message", null,
                Collections.emptyList());
        return new SanitizedAiEvidence(evidence);
    }

    private static AiAnalysisResult analysisResult() {
        return new AiAnalysisResult(AiJudgement.values()[0], ProblemLevel.values()[0],
                "CODE", "title", "summary", "basis", "root cause", "impact",
                "recommendation", "verification", "uncertainty", null, 0.9D, false, null,
                "openai", "gpt-5.6-sol", "ai-issue-v2", "{}", "request-8", "stop",
                Integer.valueOf(10), Integer.valueOf(20), Integer.valueOf(30));
    }

    private static String resultHash() {
        return "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";
    }

    private static AiCallFailure retryableFailure() {
        return new AiCallFailure("NETWORK_FAILURE", "NETWORK_FAILURE",
                "provider unavailable", true, "provider-request", "gpt-5.6-sol",
                null, null, null, null, null);
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionConfiguration {

        @Bean
        AiLogAiTaskMapper taskMapper() {
            return mock(AiLogAiTaskMapper.class);
        }

        @Bean
        AiLogAiTaskItemMapper itemMapper() {
            return mock(AiLogAiTaskItemMapper.class);
        }

        @Bean
        AiLogAiCallAttemptMapper attemptMapper() {
            return mock(AiLogAiCallAttemptMapper.class);
        }

        @Bean
        AiLogManagementOperationMapper operationMapper() {
            return mock(AiLogManagementOperationMapper.class);
        }

        @Bean
        AiLogIssueGroupMapper issueMapper() {
            AiLogIssueGroupMapper mapper = mock(AiLogIssueGroupMapper.class);
            when(mapper.refreshAiState(any(), any())).thenReturn(1);
            return mapper;
        }

        @Bean
        AiLogIssueGroupGovernanceMapper governanceMapper() {
            return mock(AiLogIssueGroupGovernanceMapper.class);
        }

        @Bean
        AiLogErrorEventMapper eventMapper() {
            return mock(AiLogErrorEventMapper.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AiTaskPlan aiTaskPlan() {
            return new AiTaskPlan(true, "openai", "default", "gpt-5.6-sol",
                    "ai-issue-v2", "sanitizer-v1", "{\"maxTokens\":2000}",
                    200, 3, 1000L);
        }

        @Bean
        AiTaskRepository aiTaskRepository(AiLogAiTaskMapper tasks,
                AiLogAiTaskItemMapper items, AiLogAiCallAttemptMapper attempts,
                AiLogManagementOperationMapper operations,
                AiLogIssueGroupMapper issues, AiLogErrorEventMapper events,
                ObjectMapper mapper, AiTaskPlan plan, AiLogIssueGroupGovernanceMapper governance) {
            return new MybatisPlusAiTaskRepository(tasks, items, attempts, operations,
                    issues, events, mapper, plan, governance);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new RecordingTransactionManager(EVENTS);
        }
    }

    /** 只记录代理事务回调，不模拟数据库提交、回滚或隔离级别。 */
    private static final class RecordingTransactionManager
            extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;
        private final List<String> events;

        private RecordingTransactionManager(List<String> recordedEvents) {
            events = recordedEvents;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("transaction-begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("transaction-commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("transaction-rollback");
        }
    }
}
