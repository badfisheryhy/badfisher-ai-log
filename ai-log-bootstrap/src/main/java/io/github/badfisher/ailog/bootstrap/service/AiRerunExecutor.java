package io.github.badfisher.ailog.bootstrap.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.badfisher.ailog.application.ai.AiTaskJobService;
import io.github.badfisher.ailog.application.ai.AiTaskJobService.RerunExecutionResult;
import io.github.badfisher.ailog.bootstrap.config.AiAnalysisProperties;
import io.github.badfisher.ailog.domain.ai.AiTaskRepository;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogManagementOperationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 显式重跑异步协调器。
 *
 * <p>协调器本身占用一个 AI 工作线程并在该线程内顺序调用 Item，不向同一线程池
 * 二次提交后等待，因此不会形成协调线程等待自身队列的死锁。operation 与当前 Item
 * 分别续租；补偿只有在两者租约均不存活时才能取得新的 fencing 令牌。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
public class AiRerunExecutor {

    private static final int RECOVERY_SCAN_LIMIT = 20;

    private final AiRerunOperationStore operationStore;
    private final AiTaskJobService aiTaskJobService;
    private final AiTaskRepository taskRepository;
    private final Executor executor;
    private final ScheduledExecutorService leaseRenewalScheduler;
    private final ObjectMapper objectMapper;
    private final int leaseSeconds;
    private final Clock clock;

    public AiRerunExecutor(AiRerunOperationStore store,
            AiTaskJobService jobService, AiTaskRepository repository,
            @Qualifier("aiAnalysisTaskExecutor") Executor taskExecutor,
            ScheduledExecutorService renewalScheduler, ObjectMapper mapper,
            AiAnalysisProperties properties) {
        operationStore = store;
        aiTaskJobService = jobService;
        taskRepository = repository;
        executor = taskExecutor;
        leaseRenewalScheduler = renewalScheduler;
        objectMapper = mapper;
        leaseSeconds = properties.getLeaseSeconds();
        clock = Clock.systemDefaultZone();
    }

    /** 提交新建操作；拒绝时安全失败且不会清理旧 Item 结果。 */
    public void submitNew(long operationId) {
        try {
            executor.execute(() -> execute(operationId));
        } catch (RejectedExecutionException exception) {
            operationStore.failBeforeExecution(operationId,
                    resultJson("EXECUTOR_REJECTED", "异步执行器拒绝重跑操作"), now());
            log.warn("event=ai_rerun_submission_rejected AI重跑操作提交被拒绝：operationId={}",
                    operationId);
        }
    }

    /**
     * 扫描并提交已经失去 operation/Item 租约的历史 RUNNING 操作。
     *
     * @return 成功提交到执行器的操作数
     */
    public int compensateInterruptedOperations() {
        List<Long> operationIds = operationStore.findRecoverableOperationIds(
                now(), RECOVERY_SCAN_LIMIT);
        int submitted = 0;
        for (Long operationId : operationIds) {
            try {
                executor.execute(() -> execute(operationId.longValue()));
                submitted++;
            } catch (RejectedExecutionException exception) {
                log.warn("event=ai_rerun_recovery_submission_rejected AI重跑补偿提交被拒绝："
                                + "operationId={}",
                        operationId);
            }
        }
        return submitted;
    }

    /** 使用 operation fencing 令牌恢复或执行固定范围。 */
    private void execute(long operationId) {
        String executionToken = UUID.randomUUID().toString();
        LocalDateTime claimTime = now();
        AiLogManagementOperationEntity operation = operationStore.claimExecution(
                operationId, executionToken, claimTime,
                claimTime.plusSeconds(leaseSeconds));
        if (operation == null) {
            return;
        }

        AtomicReference<RuntimeException> renewalFailure =
                new AtomicReference<RuntimeException>();
        ScheduledFuture<?> renewal = scheduleOperationRenewal(
                operationId, executionToken, renewalFailure);
        try {
            taskRepository.recoverExpiredRerunItems(
                    operationId, executionToken, now());
            List<Long> itemIds = operationStore.findWaitingItemIds(operationId);
            for (Long itemId : itemIds) {
                requireActiveExecution(renewalFailure);
                if (Thread.currentThread().isInterrupted()) {
                    failInterrupted(operationId, executionToken);
                    return;
                }
                RerunExecutionResult result = aiTaskJobService.executeRerunItem(
                        operationId, executionToken, itemId.longValue());
                if (!result.isExecuted()) {
                    throw new IllegalStateException(
                            "AI rerun item claim changed before execution: " + itemId);
                }
                operation = refreshProgressOrThrow(operationId, executionToken);
            }
            requireActiveExecution(renewalFailure);
            operation = refreshProgressOrThrow(operationId, executionToken);
            AiLogManagementOperationEntity finished = operationStore.finish(operationId,
                    executionToken, resultJson(operation), now());
            if (finished == null) {
                throw new IllegalStateException(
                        "AI rerun operation still has unfinished items: " + operationId);
            }
            log.info("event=ai_rerun_operation_finished AI重跑操作完成：operationId={}, "
                            + "status={}, totalCount={}, successCount={}, failedCount={}",
                    operationId, finished.getStatus(), finished.getTotalCount(),
                    finished.getSuccessCount(), finished.getFailedCount());
        } catch (RuntimeException exception) {
            operationStore.releaseForRecovery(operationId, executionToken,
                    resultJson("EXECUTION_INTERRUPTED", exception.getClass().getSimpleName()),
                    now());
            log.error("event=ai_rerun_operation_interrupted AI重跑操作异常中断，等待补偿："
                            + "operationId={}, errorType={}",
                    operationId, exception.getClass().getSimpleName(), exception);
        } finally {
            renewal.cancel(false);
        }
    }

    /** 刷新当前执行令牌的进度；令牌失效时交由原异常分支释放并等待补偿。 */
    private AiLogManagementOperationEntity refreshProgressOrThrow(long operationId, String executionToken) {
        AiLogManagementOperationEntity operation = operationStore.refreshProgress(
                operationId, executionToken, now());
        if (operation == null) {
            throw new IllegalStateException(
                    "AI rerun operation claim became stale: " + operationId);
        }
        return operation;
    }

    private ScheduledFuture<?> scheduleOperationRenewal(long operationId,
            String executionToken, AtomicReference<RuntimeException> renewalFailure) {
        long intervalSeconds = Math.max(1L, leaseSeconds / 3L);
        return leaseRenewalScheduler.scheduleAtFixedRate(() -> {
            try {
                LocalDateTime renewalTime = now();
                boolean renewed = operationStore.renewExecution(operationId,
                        executionToken, renewalTime,
                        renewalTime.plusSeconds(leaseSeconds));
                if (!renewed) {
                    throw new IllegalStateException(
                            "AI rerun operation lease became stale: " + operationId);
                }
            } catch (RuntimeException exception) {
                renewalFailure.compareAndSet(null, exception);
                throw exception;
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void failInterrupted(long operationId, String executionToken) {
        boolean failed = operationStore.failInterrupted(operationId, executionToken,
                resultJson("THREAD_INTERRUPTED", "协调线程被中断，未开始Item保持原结果"),
                now());
        if (!failed) {
            operationStore.releaseForRecovery(operationId, executionToken,
                    resultJson("THREAD_INTERRUPTED", "存在运行中Item，等待租约补偿"), now());
        }
    }

    private static void requireActiveExecution(
            AtomicReference<RuntimeException> renewalFailure) {
        RuntimeException failure = renewalFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    private String resultJson(AiLogManagementOperationEntity operation) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("totalCount", valueOrZero(operation.getTotalCount()));
        result.put("completedCount", valueOrZero(operation.getCompletedCount()));
        result.put("successCount", valueOrZero(operation.getSuccessCount()));
        result.put("failedCount", valueOrZero(operation.getFailedCount()));
        return writeJson(result);
    }

    private String resultJson(String code, String message) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("code", code);
        result.put("message", message);
        return writeJson(result);
    }

    private String writeJson(ObjectNode result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize AI rerun operation result",
                    exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value.intValue();
    }
}
