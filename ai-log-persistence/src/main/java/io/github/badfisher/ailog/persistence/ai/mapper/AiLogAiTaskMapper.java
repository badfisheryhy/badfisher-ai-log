package io.github.badfisher.ailog.persistence.ai.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskEntity;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** AI 分析批次 Mapper。 */
@Mapper
public interface AiLogAiTaskMapper extends BaseMapper<AiLogAiTaskEntity> {
    /** 自动分析业务日期来自已成功的解析任务，不复制到 AI Task。 */
    LocalDate selectAnalysisLogDate(@Param("analysisTaskId") Long analysisTaskId);

    Long selectNextTaskId(@Param("afterTaskId") Long afterTaskId);
    /** 当前读锁定重跑目标 Task 并返回完整状态。 */
    AiLogAiTaskEntity lockByIdForRerun(@Param("taskId") Long taskId);
    /** 锁定父任务，保证并发结果事务采用一致的加锁顺序。 */
    Long lockForSummaryUpdate(@Param("taskId") Long taskId);
    int markRunning(@Param("taskId") Long taskId, @Param("now") LocalDateTime now);
    int refreshSummary(@Param("taskId") Long taskId, @Param("now") LocalDateTime now);
    List<AiTaskDeliveryCandidate> selectDeliveryCandidates(
            @Param("afterTaskId") Long afterTaskId,
            @Param("staleBefore") LocalDateTime staleBefore, @Param("limit") int limit);
    AiTaskDeliveryCandidate selectClaimedDelivery(@Param("taskId") Long taskId,
            @Param("claimToken") String claimToken);
    int claimDelivery(@Param("taskId") Long taskId, @Param("claimToken") String claimToken,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("now") LocalDateTime now);
    int completeDelivery(@Param("taskId") Long taskId,
            @Param("claimToken") String claimToken, @Param("objectKey") String objectKey,
            @Param("now") LocalDateTime now);
    int failDelivery(@Param("taskId") Long taskId,
            @Param("claimToken") String claimToken, @Param("objectKey") String objectKey,
            @Param("errorMessage") String errorMessage, @Param("now") LocalDateTime now);

    /** 终态 AI 批次及来源日志日期。 */
    @Data
    final class AiTaskDeliveryCandidate {
        private Long aiTaskId;
        private String taskNo;
        private Long analysisTaskId;
        private String environment;
        private String systemCode;
        private String moduleCode;
        private LocalDate logDate;
        private String providerCode;
        private String modelCode;
        private String status;
        private Long candidateCount;
        private Integer totalCount;
        private Integer successCount;
        private Integer failedCount;
        private Integer totalAttemptCount;
        private Long totalTokenCount;
    }
}
