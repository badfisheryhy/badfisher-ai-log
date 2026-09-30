package io.github.badfisher.ailog.application.ai;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import io.github.badfisher.ailog.analysis.ai.AiEvidenceBuilder;
import io.github.badfisher.ailog.analysis.ai.EvidenceHashGenerator;
import io.github.badfisher.ailog.domain.ai.AiAnalysisProvider;
import io.github.badfisher.ailog.domain.ai.AiAnalysisProviderRegistry;
import io.github.badfisher.ailog.domain.ai.AiAnalysisRequest;
import io.github.badfisher.ailog.domain.ai.AiAnalysisResult;
import io.github.badfisher.ailog.domain.ai.AiCallFailure;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence;
import io.github.badfisher.ailog.domain.ai.AiProviderException;
import io.github.badfisher.ailog.domain.ai.AiProviderException.ErrorType;
import io.github.badfisher.ailog.domain.ai.AiTaskClaim;
import io.github.badfisher.ailog.domain.ai.AiTaskEvidenceContext;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.domain.ai.AiTaskRepository;
import io.github.badfisher.ailog.domain.ai.SanitizedAiEvidence;
import io.github.badfisher.ailog.domain.text.ExceptionMessages;

/**
 * AI Item 的短事务领取与异步调用编排。
 *
 * <p>数据库事务由仓储方法分别控制，Provider HTTP 调用不处于数据库事务中。
 * 按 AI Task 逐个处理，每个任务最多 200 个 Group，每页默认查询 50 个 Item ID，
 * 再按工作线程数认领、执行并等待落库，避免尚未执行的分页记录提前占用租约。</p>
 *
 * <p>同一轮内每个 Item 最多调用一次，可重试失败由仓储安排同 Item 下次调度，其他失败落为 FAILED；继续后续 Item 和分页。
 * 线程池拒绝时归还已认领但未提交的租约并停止调度。</p>
 */
@Slf4j
public final class AiTaskJobService {
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    private final AiTaskRepository repository;
    private final AiAnalysisProviderRegistry providerRegistry;
    private final AiEvidenceBuilder evidenceBuilder;
    private final AiEvidenceSanitizer evidenceSanitizer;
    private final EvidenceHashGenerator hashGenerator;
    private final AiTaskBlameEnrichmentService blameEnrichmentService;
    private final Executor executor;
    private final ScheduledExecutorService leaseRenewalScheduler;
    private final int pageSize;
    private final int sampleLimit;
    private final int parallelism;
    private final int leaseSeconds;
    private final String workerId;
    private final Clock clock;
    private final java.util.function.UnaryOperator<AiIssueEvidence> contextEnricher;

    /**
     * 创建 AI 任务调度服务，并固定本次进程使用的并发、租约和取样边界。
     */
    public AiTaskJobService(AiTaskRepository taskRepository,
            AiAnalysisProviderRegistry registry, AiEvidenceBuilder builder,
            AiEvidenceSanitizer sanitizer, EvidenceHashGenerator generator,
            AiTaskBlameEnrichmentService blameEnricher,
            Executor taskExecutor, ScheduledExecutorService renewalScheduler,
            int itemPageSize, int maxSamples,
            int concurrentWorkers, int itemLeaseSeconds, String owner, Clock serviceClock) {
        this(taskRepository, registry, builder, sanitizer, generator, blameEnricher, taskExecutor,
                renewalScheduler, itemPageSize, maxSamples, concurrentWorkers, itemLeaseSeconds,
                owner, serviceClock, java.util.function.UnaryOperator.identity());
    }

    public AiTaskJobService(AiTaskRepository taskRepository,
            AiAnalysisProviderRegistry registry, AiEvidenceBuilder builder,
            AiEvidenceSanitizer sanitizer, EvidenceHashGenerator generator,
            AiTaskBlameEnrichmentService blameEnricher,
            Executor taskExecutor, ScheduledExecutorService renewalScheduler,
            int itemPageSize, int maxSamples, int concurrentWorkers, int itemLeaseSeconds,
            String owner, Clock serviceClock,
            java.util.function.UnaryOperator<AiIssueEvidence> contextEnricher) {
        this.contextEnricher = java.util.Objects.requireNonNull(contextEnricher);
        if (itemPageSize < 1 || itemPageSize > AiTaskPlan.HARD_DISPATCH_LIMIT
                || concurrentWorkers < 1) {
            throw new IllegalArgumentException("AI page size must be within [1,50] and workers positive");
        }
        repository = taskRepository;
        providerRegistry = registry;
        evidenceBuilder = builder;
        evidenceSanitizer = sanitizer;
        hashGenerator = generator;
        blameEnrichmentService = blameEnricher;
        executor = taskExecutor;
        leaseRenewalScheduler = renewalScheduler;
        pageSize = itemPageSize;
        sampleLimit = maxSamples;
        parallelism = concurrentWorkers;
        leaseSeconds = itemLeaseSeconds;
        workerId = owner;
        clock = serviceClock;
    }

    /** 遍历未完成的 AI Task；每个任务独立分页，200 个 Group 上限不跨任务共用。 */
    public DispatchResult dispatch() {
        int prepared = 0;
        int recovered = repository.recoverExpiredLeases(now());
        int claimed = 0;
        int submitted = 0;
        int rejected = 0;
        int succeeded = 0;
        int failed = 0;
        int retriesScheduled = 0;

        long afterTaskId = 0L;
        Long taskId;
        while ((taskId = repository.findNextTaskId(afterTaskId)) != null) {
            DispatchResult task = dispatchTask(taskId.longValue());
            prepared += task.getPreparedCount();
            claimed += task.getClaimedCount();
            submitted += task.getSubmittedCount();
            rejected += task.getRejectedCount();
            succeeded += task.getSuccessCount();
            failed += task.getFailureCount();
            retriesScheduled += task.getRetryScheduledCount();
            afterTaskId = taskId.longValue();
            if (task.getRejectedCount() > 0) {
                break;
            }
        }
        return new DispatchResult(prepared, recovered, claimed, submitted, rejected,
                succeeded, failed, retriesScheduled);
    }

    /** 完成当前任务的一页后再取下一页；ID 游标防止失败记录被本轮重复调用。 */
    private DispatchResult dispatchTask(long taskId) {
        int prepared = repository.prepareTask(taskId, now()) ? 1 : 0;
        int scanned = 0;
        int claimed = 0;
        int submitted = 0;
        int rejected = 0;
        int succeeded = 0;
        int failed = 0;
        int retriesScheduled = 0;
        long afterItemId = 0L;

        while (scanned < AiTaskPlan.HARD_SELECTION_LIMIT && rejected == 0) {
            int limit = Math.min(pageSize, AiTaskPlan.HARD_SELECTION_LIMIT - scanned);
            List<Long> itemIds = repository.findReadyItemIds(taskId, afterItemId, limit, now());
            if (itemIds.isEmpty()) {
                break;
            }
            for (int offset = 0; offset < itemIds.size(); offset += parallelism) {
                int end = Math.min(offset + parallelism, itemIds.size());
                LocalDateTime claimTime = now();
                List<AiTaskClaim> claims = repository.claimReadyItems(
                        taskId, itemIds.subList(offset, end), workerId,
                        claimTime, claimTime.plusSeconds(leaseSeconds));
                BatchExecutionResult batch = executeAndWait(claims);
                claimed += claims.size();
                submitted += batch.submittedCount;
                rejected += batch.rejectedCount;
                succeeded += batch.successCount;
                failed += batch.failureCount;
                retriesScheduled += batch.retryScheduledCount;
                if (batch.rejectedCount > 0) {
                    break;
                }
            }
            scanned += itemIds.size();
            afterItemId = itemIds.get(itemIds.size() - 1).longValue();
        }
        return new DispatchResult(prepared, 0, claimed, submitted, rejected,
                succeeded, failed, retriesScheduled);
    }

    private BatchExecutionResult executeAndWait(List<AiTaskClaim> claims) {
        CountDownLatch completion = new CountDownLatch(claims.size());
        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger retriesScheduled = new AtomicInteger();
        AtomicReference<RuntimeException> executionFailure =
                new AtomicReference<RuntimeException>();

        for (AiTaskClaim claim : claims) {
            try {
                executor.execute(() -> {
                    try {
                        ExecutionOutcome outcome = execute(claim);
                        if (outcome == ExecutionOutcome.SUCCESS) {
                            succeeded.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                            if (outcome == ExecutionOutcome.RETRY_SCHEDULED) {
                                retriesScheduled.incrementAndGet();
                            }
                        }
                    } catch (RuntimeException ex) {
                        executionFailure.compareAndSet(null, ex);
                    } finally {
                        completion.countDown();
                    }
                });
                submitted.incrementAndGet();
            } catch (RejectedExecutionException ex) {
                rejected.incrementAndGet();
                try {
                    repository.releaseClaim(claim, "EXECUTOR_REJECTED",
                            "AI executor rejected the claimed item", now());
                } catch (RuntimeException releaseFailure) {
                    executionFailure.compareAndSet(null, releaseFailure);
                } finally {
                    completion.countDown();
                }
                log.warn("event=ai_item_submission_rejected AI Item 提交被线程池拒绝：aiTaskId={}, itemId={}",
                        claim.getAiTaskId(), claim.getItemId());
            }
        }
        await(completion);
        RuntimeException failure = executionFailure.get();
        if (failure != null) {
            throw failure;
        }
        return new BatchExecutionResult(submitted.get(), rejected.get(), succeeded.get(),
                failed.get(), retriesScheduled.get());
    }

    private ExecutionOutcome execute(AiTaskClaim claim) {
        return execute(claim, null);
    }

    /**
     * 执行管理操作已经固定范围的单个 Item 重跑。
     *
     * <p>该入口复用正常任务的 Provider、证据、脱敏和租约续租能力，但使用专用
     * 持久化方法，不读取父 Task 终态、不受历史 maxAttempts 限制，也不刷新父 Task
     * 汇总或报告状态。</p>
     *
     * @param operationId             管理操作 ID
     * @param operationExecutionToken 管理操作本轮执行令牌
     * @param itemId                  原 AI Item ID
     * @return 本次是否实际执行及成功状态
     */
    public RerunExecutionResult executeRerunItem(long operationId,
            String operationExecutionToken, long itemId) {
        LocalDateTime claimTime = now();
        AiTaskClaim claim = repository.claimRerunItem(operationId,
                operationExecutionToken, itemId, workerId, claimTime,
                claimTime.plusSeconds(leaseSeconds));
        if (claim == null) {
            return RerunExecutionResult.notClaimed(itemId);
        }
        ExecutionOutcome outcome = execute(claim,
                new RerunContext(operationId, operationExecutionToken));
        return outcome == ExecutionOutcome.SUCCESS
                ? RerunExecutionResult.success(itemId)
                : RerunExecutionResult.failed(itemId);
    }

    /** 复用正常任务和显式重跑共同的取证、Provider 调用和租约续租流程。 */
    private ExecutionOutcome execute(AiTaskClaim claim, RerunContext rerun) {
        long startedNanos = System.nanoTime();
        AiAnalysisProvider provider;
        SanitizedAiEvidence sanitized;
        String evidenceHash;
        try {
            provider = providerRegistry.get(claim.getProviderCode(), claim.getModelCode());
            AiTaskEvidenceContext context = repository.loadEvidence(claim, sampleLimit);
            AiIssueEvidence evidence = evidenceBuilder.build(
                    context.getSnapshot(), context.getRepresentativeEvents());
            sanitized = evidenceSanitizer.sanitize(contextEnricher.apply(evidence));
            evidenceHash = hashGenerator.hash(
                    sanitized, claim.getPromptVersion(), claim.getSanitizerVersion());
        } catch (AiEvidenceSanitizationException ex) {
            failBeforeCall(claim, rerun, "SANITIZE_FAILED",
                    ExceptionMessages.singleLine(ex, ERROR_MESSAGE_MAX_LENGTH));
            log.error("event=ai_evidence_sanitization_failed AI 证据脱敏失败："
                            + "operationId={}, aiTaskId={}, itemId={}, issueId={}",
                    operationId(rerun), claim.getAiTaskId(), claim.getItemId(),
                    ex.getIssueId(), ex);
            return ExecutionOutcome.FAILED;
        } catch (Exception ex) {
            failBeforeCall(claim, rerun, "PREPARE_FAILED",
                    ExceptionMessages.singleLine(ex, ERROR_MESSAGE_MAX_LENGTH));
            log.error("event=ai_item_preparation_failed AI Item 调用前准备失败："
                            + "operationId={}, aiTaskId={}, itemId={}",
                    operationId(rerun), claim.getAiTaskId(), claim.getItemId(), ex);
            return ExecutionOutcome.FAILED;
        }

        long attemptId = startAttempt(claim, rerun, evidenceHash, sanitized);
        AtomicReference<RuntimeException> leaseRenewalFailure =
                new AtomicReference<RuntimeException>();
        AtomicBoolean leaseRenewalClosed = new AtomicBoolean(false);
        Object leaseRenewalMonitor = new Object();
        ScheduledFuture<?> leaseRenewal = scheduleLeaseRenewal(claim,
                leaseRenewalFailure, leaseRenewalClosed, leaseRenewalMonitor);
        AiAnalysisResult result = null;
        Exception providerFailure = null;
        try {
            blameEnrichmentService.enrich(claim, operationId(rerun),
                    rerun == null ? null : rerun.executionToken);
            result = provider.analyze(
                    new AiAnalysisRequest(sanitized, claim.getPromptVersion()));
        } catch (Exception ex) {
            providerFailure = ex;
        } finally {
            leaseRenewalClosed.set(true);
            synchronized (leaseRenewalMonitor) {
                leaseRenewal.cancel(false);
            }
        }
        RuntimeException renewalFailure = leaseRenewalFailure.get();
        if (renewalFailure != null) {
            throw renewalFailure;
        }
        if (providerFailure != null) {
            return handleProviderFailure(claim, rerun, attemptId, providerFailure,
                    elapsedMillis(startedNanos));
        }

        try {
            completeSuccess(claim, rerun, attemptId, result,
                    elapsedMillis(startedNanos));
            return ExecutionOutcome.SUCCESS;
        } catch (Exception ex) {
            log.error("event=ai_result_persistence_failed AI 结果持久化失败："
                            + "operationId={}, aiTaskId={}, itemId={}, attemptId={}",
                    operationId(rerun), claim.getAiTaskId(), claim.getItemId(), attemptId, ex);
            throw ex;
        }
    }

    /** 按正常调度或显式重跑契约记录调用前失败。 */
    private void failBeforeCall(AiTaskClaim claim, RerunContext rerun,
            String errorCode, String errorMessage) {
        if (rerun == null) {
            repository.failBeforeCall(claim, errorCode, errorMessage, now());
            return;
        }
        repository.failRerunBeforeCall(claim, rerun.operationId,
                rerun.executionToken, errorCode, errorMessage, now());
    }

    /** 按正常调度或显式重跑契约开始 Attempt。 */
    private long startAttempt(AiTaskClaim claim, RerunContext rerun,
            String evidenceHash, SanitizedAiEvidence sanitized) {
        String requestId = UUID.randomUUID().toString();
        if (rerun == null) {
            return repository.startAttempt(claim, requestId, evidenceHash, sanitized, now());
        }
        return repository.startRerunAttempt(claim, rerun.operationId,
                rerun.executionToken, requestId, evidenceHash, sanitized, now());
    }

    /** 按正常调度或显式重跑契约完成成功结果。 */
    private void completeSuccess(AiTaskClaim claim, RerunContext rerun,
            long attemptId, AiAnalysisResult result, long latencyMillis) {
        if (rerun == null) {
            repository.completeSuccess(claim, attemptId, result, latencyMillis, now());
            return;
        }
        repository.completeRerunSuccess(claim, rerun.operationId,
                rerun.executionToken, attemptId, result, latencyMillis, now());
    }

    private ExecutionOutcome handleProviderFailure(AiTaskClaim claim, RerunContext rerun,
            long attemptId, Exception exception, long latencyMillis) {
        String errorMessage =
                ExceptionMessages.singleLine(exception, ERROR_MESSAGE_MAX_LENGTH);
        AiProviderException providerException = exception instanceof AiProviderException
                ? (AiProviderException) exception : null;
        ErrorType errorType = providerException == null
                ? ErrorType.REQUEST_ERROR : providerException.getErrorType();
        boolean retryable = rerun == null && (errorType == ErrorType.NETWORK_FAILURE
                || errorType == ErrorType.RATE_LIMITED || errorType == ErrorType.SERVER_ERROR);
        AiCallFailure failure = new AiCallFailure(errorType.name(), errorType.name(),
                errorMessage, retryable,
                providerException == null ? null : providerException.getProviderRequestId(),
                providerException == null ? null : providerException.getActualModel(),
                providerException == null ? null : providerException.getRawResponse(),
                providerException == null ? null : providerException.getFinishReason(),
                providerException == null ? null : providerException.getInputTokens(),
                providerException == null ? null : providerException.getOutputTokens(),
                providerException == null ? null : providerException.getTotalTokens());
        if (rerun == null) {
            repository.completeFailure(claim, attemptId, failure, latencyMillis, now());
        } else {
            repository.completeRerunFailure(claim, rerun.operationId,
                    rerun.executionToken, attemptId, failure, latencyMillis, now());
        }
        log.warn("event=ai_provider_call_failed AI Provider 调用失败："
                        + "operationId={}, aiTaskId={}, itemId={}, attemptNo={}, errorType={}",
                operationId(rerun), claim.getAiTaskId(), claim.getItemId(),
                claim.getAttemptCount() + 1, errorType, exception);
        return retryable && claim.getAttemptCount() + 1 < claim.getMaxAttempts()
                ? ExecutionOutcome.RETRY_SCHEDULED : ExecutionOutcome.FAILED;
    }

    private static Long operationId(RerunContext rerun) {
        return rerun == null ? null : Long.valueOf(rerun.operationId);
    }

    /** Provider 阻塞调用期间按租约三分之一周期续租，防止正常长调用被恢复。 */
    private ScheduledFuture<?> scheduleLeaseRenewal(AiTaskClaim claim,
            AtomicReference<RuntimeException> renewalFailure,
            AtomicBoolean renewalClosed, Object renewalMonitor) {
        long renewalIntervalSeconds = Math.max(1L, leaseSeconds / 3L);
        return leaseRenewalScheduler.scheduleAtFixedRate(() -> {
            synchronized (renewalMonitor) {
                if (renewalClosed.get()) {
                    return;
                }
                try {
                    LocalDateTime renewalTime = now();
                    repository.renewLease(claim, renewalTime.plusSeconds(leaseSeconds),
                            renewalTime);
                } catch (RuntimeException ex) {
                    renewalFailure.compareAndSet(null, ex);
                    throw ex;
                }
            }
        }, renewalIntervalSeconds, renewalIntervalSeconds, TimeUnit.SECONDS);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static void await(CountDownLatch completion) {
        try {
            completion.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI任务批量等待被中断", ex);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    /** 单次调度结果。 */
    @Getter
    public static final class DispatchResult {
        private final int preparedCount;
        private final int recoveredCount;
        private final int claimedCount;
        private final int submittedCount;
        private final int rejectedCount;
        private final int successCount;
        private final int failureCount;
        private final int retryScheduledCount;

        /** 构造单次调度的计数快照。 */
        public DispatchResult(int prepared, int recovered, int claimed,
                int submitted, int rejected, int succeeded, int failed,
                int retriesScheduled) {
            preparedCount = prepared;
            recoveredCount = recovered;
            claimedCount = claimed;
            submittedCount = submitted;
            rejectedCount = rejected;
            successCount = succeeded;
            failureCount = failed;
            retryScheduledCount = retriesScheduled;
        }

    }

    /** 单个显式重跑 Item 的执行结果。 */
    @Getter
    public static final class RerunExecutionResult {
        private final long itemId;
        private final boolean executed;
        private final boolean success;

        private RerunExecutionResult(long id, boolean wasExecuted, boolean succeeded) {
            itemId = id;
            executed = wasExecuted;
            success = succeeded;
        }

        private static RerunExecutionResult notClaimed(long itemId) {
            return new RerunExecutionResult(itemId, false, false);
        }

        private static RerunExecutionResult success(long itemId) {
            return new RerunExecutionResult(itemId, true, true);
        }

        private static RerunExecutionResult failed(long itemId) {
            return new RerunExecutionResult(itemId, true, false);
        }
    }

    /** 显式重跑操作级 fencing 上下文。 */
    private static final class RerunContext {
        private final long operationId;
        private final String executionToken;

        private RerunContext(long id, String token) {
            operationId = id;
            executionToken = token;
        }
    }

    private enum ExecutionOutcome {
        SUCCESS,
        RETRY_SCHEDULED,
        FAILED
    }

    private static final class BatchExecutionResult {
        private final int submittedCount;
        private final int rejectedCount;
        private final int successCount;
        private final int failureCount;
        private final int retryScheduledCount;

        private BatchExecutionResult(int submitted, int rejected, int succeeded,
                int failed, int retriesScheduled) {
            submittedCount = submitted;
            rejectedCount = rejected;
            successCount = succeeded;
            failureCount = failed;
            retryScheduledCount = retriesScheduled;
        }
    }
}
