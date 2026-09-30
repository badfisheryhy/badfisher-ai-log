package io.github.badfisher.ailog.persistence.ai.mapper;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogManagementOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 管理端操作审计 Mapper。 */
@Mapper
public interface AiLogManagementOperationMapper
        extends BaseMapper<AiLogManagementOperationEntity> {

    /** 按客户端幂等请求 ID 查询操作记录。 */
    AiLogManagementOperationEntity selectLatestByRequestId(
            @Param("requestId") String requestId);

    /** 查询并锁定同一父 Task 的运行中重跑操作。 */
    AiLogManagementOperationEntity selectRunningRerunByTaskForUpdate(
            @Param("taskId") String taskId);

    /** 锁定并校验当前重跑执行令牌，统一 operation -> Item/Attempt 加锁顺序。 */
    Long lockActiveRerunExecution(@Param("operationId") Long operationId,
            @Param("executionToken") String executionToken,
            @Param("now") LocalDateTime now);

    /** 使用 fencing 令牌认领未执行或已过期且没有存活 Item 租约的操作。 */
    int claimRerunExecution(@Param("operationId") Long operationId,
            @Param("executionToken") String executionToken,
            @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 续租当前操作执行器。 */
    int renewRerunExecution(@Param("operationId") Long operationId,
            @Param("executionToken") String executionToken,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now);

    /** 从 Item 重跑子状态重新计算管理操作进度。 */
    int refreshRerunProgress(@Param("operationId") Long operationId,
            @Param("executionToken") String executionToken,
            @Param("now") LocalDateTime now);

    /** 在所有固定范围 Item 结束后将操作原子置为终态。 */
    int finishRerunOperation(@Param("operationId") Long operationId,
            @Param("executionToken") String executionToken,
            @Param("resultJson") String resultJson,
            @Param("now") LocalDateTime now);

    /** 首次异步提交被拒绝时失败操作；此时尚未清理任何旧结果。 */
    int failRerunBeforeExecution(@Param("operationId") Long operationId,
            @Param("resultJson") String resultJson,
            @Param("now") LocalDateTime now);

    /** 线程被中断且没有运行中 Item 时取消剩余范围并失败操作。 */
    int failInterruptedRerun(@Param("operationId") Long operationId,
            @Param("executionToken") String executionToken,
            @Param("resultJson") String resultJson,
            @Param("now") LocalDateTime now);

    /** 释放异常退出执行器，保留 RUNNING 状态供后续补偿。 */
    int releaseRerunForRecovery(@Param("operationId") Long operationId,
            @Param("executionToken") String executionToken,
            @Param("resultJson") String resultJson,
            @Param("now") LocalDateTime now);

    /** 查询可安全接管的中断操作。 */
    List<Long> selectRecoverableRerunOperationIds(
            @Param("now") LocalDateTime now, @Param("limit") int limit);
}
