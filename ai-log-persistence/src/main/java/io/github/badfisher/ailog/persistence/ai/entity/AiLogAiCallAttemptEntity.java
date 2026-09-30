package io.github.badfisher.ailog.persistence.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 一次真实 AI HTTP 调用的审计记录。 */
@Data
@TableName("tb_ai_log_ai_call_attempt")
public class AiLogAiCallAttemptEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long aiTaskItemId;
    /** 显式重跑操作 ID；普通调度 Attempt 为空。 */
    private Long rerunOperationId;
    private Integer attemptNo;
    private String requestId;
    private String providerCode;
    private String requestedModel;
    private String actualModel;
    private String providerRequestId;
    private String requestMode;
    private String requestHash;
    private String status;
    private String responsePayload;
    private Integer httpStatus;
    private String finishReason;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private Long latencyMillis;
    private String errorType;
    private String errorCode;
    private String errorMessage;
    private Boolean retryable;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
