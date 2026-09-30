package io.github.badfisher.ailog.persistence.ai.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskItemEntity;
import io.github.badfisher.ailog.domain.ai.AiCallFailure;
import io.github.badfisher.ailog.domain.ai.GitBlameResult;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** AI 分析 Item Mapper。 */
@Mapper
public interface AiLogAiTaskItemMapper extends BaseMapper<AiLogAiTaskItemEntity> {
    /** Group 锁内批量复查当日自动分析；手工 rerun 不占用自动分析日期。 */
    List<Long> selectAutomaticGroupIds(@Param("groupIds") List<Long> groupIds,
            @Param("logDate") LocalDate logDate);

    /** 按父 Task 与 Group 锁定原 Item。 */
    AiLogAiTaskItemEntity lockByTaskAndIssueGroup(@Param("taskId") Long taskId,
            @Param("issueGroupId") Long issueGroupId);
    /** 锁定整 Task 的全部原 Item，固定显式重跑范围。 */
    List<AiLogAiTaskItemEntity> lockTaskItemsForRerun(@Param("taskId") Long taskId);
    /** 父 Task 已加锁时获取新增执行记录的起始顺序。 */
    int selectMaxSelectionOrder(@Param("taskId") Long taskId);
    /** ID 游标分页只读查询；状态变化不会造成 OFFSET 分页跳过记录。 */
    List<Long> selectReadyItemIds(@Param("taskId") long taskId,
            @Param("afterItemId") long afterItemId,
            @Param("now") LocalDateTime now, @Param("limit") int limit);
    List<AiTaskDispatchCandidate> selectReadyCandidates(
            @Param("taskId") long taskId, @Param("itemIds") List<Long> itemIds,
            @Param("now") LocalDateTime now);
    int claim(@Param("itemId") Long itemId, @Param("claimToken") String claimToken,
            @Param("leaseOwner") String leaseOwner, @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil);
    int renewLease(@Param("itemId") Long itemId,
            @Param("claimToken") String claimToken,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now);
    int updateBlame(@Param("itemId") Long itemId,
            @Param("claimToken") String claimToken,
            @Param("operationId") Long operationId,
            @Param("operationExecutionToken") String operationExecutionToken,
            @Param("result") GitBlameResult result,
            @Param("now") LocalDateTime now);
    int markAttemptStarted(@Param("itemId") Long itemId,
            @Param("claimToken") String claimToken, @Param("expectedAttemptCount") int expected,
            @Param("evidenceHash") String evidenceHash,
            @Param("evidenceSnapshotJson") String evidenceSnapshotJson,
            @Param("now") LocalDateTime now);
    int completeSuccess(@Param("itemId") Long itemId,
            @Param("claimToken") String claimToken,
            @Param("result") io.github.badfisher.ailog.domain.ai.AiAnalysisResult result,
            @Param("latencyMillis") long latencyMillis, @Param("resultHash") String resultHash,
            @Param("now") LocalDateTime now);
    int completeFailure(@Param("itemId") Long itemId,
            @Param("claimToken") String claimToken,
            @Param("failure") AiCallFailure failure,
            @Param("latencyMillis") long latencyMillis, @Param("now") LocalDateTime now,
            @Param("nextRetryTime") LocalDateTime nextRetryTime);
    int failBeforeCall(@Param("itemId") Long itemId,
            @Param("claimToken") String claimToken, @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage, @Param("now") LocalDateTime now);
    int releaseClaim(@Param("itemId") Long itemId,
            @Param("claimToken") String claimToken, @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage, @Param("now") LocalDateTime now);

    /** 释放尚未开始的重跑范围，保留原 Item 结果。 */
    int clearWaitingRerunReservations(@Param("operationId") Long operationId,
            @Param("now") LocalDateTime now);

    /** 按固定范围顺序查询尚未执行的 Item。 */
    List<Long> selectRerunWaitingItemIds(@Param("operationId") Long operationId);

    /** 查询操作执行令牌下可领取的重跑 Item 配置。 */
    AiTaskDispatchCandidate selectRerunCandidate(
            @Param("operationId") Long operationId,
            @Param("operationExecutionToken") String operationExecutionToken,
            @Param("itemId") Long itemId);

    /** 领取本次新建的重跑 Item，不修改历史分析记录。 */
    int claimRerun(@Param("operationId") Long operationId,
            @Param("operationExecutionToken") String operationExecutionToken,
            @Param("itemId") Long itemId, @Param("claimToken") String claimToken,
            @Param("leaseOwner") String leaseOwner, @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 显式重跑开始 Attempt；历史 attemptCount 只递增、不校验 maxAttempts。 */
    int markRerunAttemptStarted(@Param("operationId") Long operationId,
            @Param("operationExecutionToken") String operationExecutionToken,
            @Param("itemId") Long itemId, @Param("claimToken") String claimToken,
            @Param("expectedAttemptCount") int expected,
            @Param("evidenceHash") String evidenceHash,
            @Param("evidenceSnapshotJson") String evidenceSnapshotJson,
            @Param("now") LocalDateTime now);

    int completeRerunSuccess(@Param("operationId") Long operationId,
            @Param("operationExecutionToken") String operationExecutionToken,
            @Param("itemId") Long itemId, @Param("claimToken") String claimToken,
            @Param("result") io.github.badfisher.ailog.domain.ai.AiAnalysisResult result,
            @Param("latencyMillis") long latencyMillis,
            @Param("resultHash") String resultHash, @Param("now") LocalDateTime now);

    int completeRerunFailure(@Param("operationId") Long operationId,
            @Param("operationExecutionToken") String operationExecutionToken,
            @Param("itemId") Long itemId, @Param("claimToken") String claimToken,
            @Param("failure") AiCallFailure failure,
            @Param("latencyMillis") long latencyMillis, @Param("now") LocalDateTime now);

    int failRerunBeforeCall(@Param("operationId") Long operationId,
            @Param("operationExecutionToken") String operationExecutionToken,
            @Param("itemId") Long itemId, @Param("claimToken") String claimToken,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage, @Param("now") LocalDateTime now);

    /** 专用恢复一个操作中已经过期的重跑 Item。 */
    int recoverExpiredRerunItems(@Param("operationId") Long operationId,
            @Param("now") LocalDateTime now);
    List<Long> selectExpiredTaskIds(@Param("now") LocalDateTime now);
    int recoverExpired(@Param("now") LocalDateTime now);

    /** 待领取 Item 与其不可变任务配置。 */
    @Data
    final class AiTaskDispatchCandidate {
        private Long itemId;
        private Long aiTaskId;
        private Long issueGroupId;
        private String providerCode;
        private String modelCode;
        private Integer attemptCount;
        private Integer maxAttempts;
    }
}
