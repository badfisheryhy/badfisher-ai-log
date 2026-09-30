package io.github.badfisher.ailog.bootstrap.controller.response;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** AI 显式重跑操作及实时进度。 */
@Data
@Schema(name = "AI显式重跑操作进度")
public class AiRerunOperationResponse {

    @Schema(description = "管理操作ID")
    private Long operationId;
    @Schema(description = "客户端幂等请求ID")
    private String requestId;
    @Schema(description = "操作类型：AI_GROUP_RERUN、AI_TASK_RERUN")
    private String operationType;
    @Schema(description = "原AI Task ID")
    private Long aiTaskId;
    @Schema(description = "单Group重跑的原AI Item ID，整Task重跑为空")
    private Long sourceItemId;
    @Schema(description = "创建操作时固定的原AI Item ID范围")
    private List<Long> targetItemIds;
    @Schema(description = "操作人身份")
    private String actor;
    @Schema(description = "操作状态：RUNNING、SUCCESS、FAILED")
    private String status;
    @Schema(description = "固定范围Item总数")
    private Integer totalCount;
    @Schema(description = "已落终态Item数")
    private Integer completedCount;
    @Schema(description = "本次成功Item数")
    private Integer successCount;
    @Schema(description = "本次失败Item数")
    private Integer failedCount;
    @Schema(description = "不含敏感正文的进度或结果摘要JSON")
    private String resultJson;
    @Schema(description = "是否为同一请求的幂等重放")
    private Boolean idempotentReplay;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    @Schema(description = "最近进度更新时间")
    private LocalDateTime updateTime;
    @Schema(description = "操作完成时间")
    private LocalDateTime finishTime;
}
