package io.github.badfisher.ailog.domain.ai;

import java.time.LocalDateTime;
import java.util.List;

/** AI 任务领取、调用审计和结果持久化端口。 */
public interface AiTaskRepository {

    /** 按 ID 游标查找下一个未完成的主任务；本轮不重复处理同一任务。 */
    Long findNextTaskId(long afterTaskId);

    /** 准备指定的 PENDING 主任务；已准备过的任务返回 false。 */
    boolean prepareTask(long taskId, LocalDateTime now);

    /** 恢复租约已过期的运行中 Item，并返回恢复数量。 */
    int recoverExpiredLeases(LocalDateTime now);

    /** 分页查询指定任务的可执行 Item ID，不提前占用租约。 */
    List<Long> findReadyItemIds(long taskId, long afterItemId, int limit, LocalDateTime now);

    /** 仅认领当前准备提交到工作线程的 Item，防止分页中排队的 Item 租约过期。 */
    List<AiTaskClaim> claimReadyItems(long taskId, List<Long> itemIds, String leaseOwner,
            LocalDateTime now, LocalDateTime leaseUntil);

    /** 使用领取令牌续租仍在运行的 Item。 */
    void renewLease(AiTaskClaim claim, LocalDateTime leaseUntil, LocalDateTime now);

    /** 加载一个已领取 Item 的确定性 Group 快照和代表样本。 */
    AiTaskEvidenceContext loadEvidence(AiTaskClaim claim, int sampleLimit);

    /** 在调用供应商前原子记录请求快照和运行中 Attempt。 */
    long startAttempt(AiTaskClaim claim, String requestId, String requestHash,
            SanitizedAiEvidence evidence, LocalDateTime now);

    /** 原子完成 Attempt、Item 和主任务成功计数。 */
    void completeSuccess(AiTaskClaim claim, long attemptId,
            AiAnalysisResult result, long latencyMillis, LocalDateTime now);

    /** 原子记录调用失败；可重试且未耗尽次数时等待同 Item 重试，否则收口为 FAILED。 */
    void completeFailure(AiTaskClaim claim, long attemptId, AiCallFailure failure,
            long latencyMillis, LocalDateTime now);

    /** 在供应商调用开始前终止无法构造请求的 Item。 */
    void failBeforeCall(AiTaskClaim claim, String errorCode,
            String errorMessage, LocalDateTime now);

    /** 归还尚未提交执行器的租约，供后续调度重新领取。 */
    void releaseClaim(AiTaskClaim claim, String errorCode,
            String errorMessage, LocalDateTime now);

    /** 认领管理操作预留的一个终态 Item；显式重跑不受历史 maxAttempts 限制。 */
    AiTaskClaim claimRerunItem(long operationId, String operationExecutionToken,
            long itemId, String leaseOwner, LocalDateTime now, LocalDateTime leaseUntil);

    /** 在调用供应商前递增历史 Attempt 序号并关联本次显式重跑操作。 */
    long startRerunAttempt(AiTaskClaim claim, long operationId,
            String operationExecutionToken, String requestId, String requestHash,
            SanitizedAiEvidence evidence, LocalDateTime now);

    /** 完成显式重跑成功结果；只更新 Item 和 Attempt，不刷新父 Task 汇总。 */
    void completeRerunSuccess(AiTaskClaim claim, long operationId,
            String operationExecutionToken, long attemptId, AiAnalysisResult result,
            long latencyMillis, LocalDateTime now);

    /** 完成显式重跑失败结果；失败直接终止，不进入普通 RETRY_WAIT。 */
    void completeRerunFailure(AiTaskClaim claim, long operationId,
            String operationExecutionToken, long attemptId, AiCallFailure failure,
            long latencyMillis, LocalDateTime now);

    /** 显式重跑在供应商调用前失败时清理运行租约并落终态。 */
    void failRerunBeforeCall(AiTaskClaim claim, long operationId,
            String operationExecutionToken, String errorCode,
            String errorMessage, LocalDateTime now);

    /** 收口指定操作中租约已过期的重跑 Item 和运行中 Attempt。 */
    int recoverExpiredRerunItems(long operationId, String operationExecutionToken,
            LocalDateTime now);

    /** 按 ID 顺序领取待投递报告；已有结果的运行中任务也可生成阶段性报告。 */
    AiTaskDeliveryReport claimNextDelivery(long afterTaskId, LocalDateTime staleBefore,
            LocalDateTime now);

    /** 使用投递令牌完成对象存储报告上传。 */
    boolean completeDelivery(long aiTaskId, String claimToken,
            String objectKey, LocalDateTime now);

    /** 使用投递令牌记录报告投递失败。 */
    boolean failDelivery(long aiTaskId, String claimToken, String objectKey,
            String errorMessage, LocalDateTime now);

}
