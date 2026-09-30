package io.github.badfisher.ailog.persistence.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** AI 分析批次实体。 */
@Data
@TableName("tb_ai_log_ai_task")
public class AiLogAiTaskEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private Long analysisTaskId;
    private String environment;
    private String systemCode;
    private String moduleCode;
    private String providerCode;
    private String modelCode;
    private Integer runNo;
    private Integer selectionLimit;
    private Long candidateCount;
    private Integer totalCount;
    private String status;
    private Boolean preparationComplete;
    private Integer successCount;
    private Integer failedCount;
    private Integer totalAttemptCount;
    private Long totalTokenCount;
    private String deliveryStatus;
    private String deliveryToken;
    private Integer deliveryAttemptCount;
    private String reportObjectKey;
    private String deliveryErrorMessage;
    private LocalDateTime deliveryStartTime;
    private LocalDateTime deliveredTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
