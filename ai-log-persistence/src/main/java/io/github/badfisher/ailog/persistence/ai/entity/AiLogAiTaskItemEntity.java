package io.github.badfisher.ailog.persistence.ai.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 一个 Issue Group 的 AI 分析 Item 及最终结论。 */
@Data
@TableName("tb_ai_log_ai_task_item")
public class AiLogAiTaskItemEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long aiTaskId;
    private Long analysisTaskId;
    private Long issueGroupId;
    private Long sampleEventId;
    private Integer selectionOrder;
    private Long occurrenceCountSnapshot;
    /** 创建本次分析时从 Event 批量聚合的首次时间，与次数、最近时间保持同一口径。 */
    private LocalDateTime firstOccurredAtSnapshot;
    private LocalDateTime lastOccurredAtSnapshot;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Integer finalAttemptNo;
    private LocalDateTime nextRetryTime;
    private String claimToken;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String evidenceHash;
    private String evidenceSnapshotJson;
    /** 源码定位行最后修改 Author，仅作归属线索。 */
    private String blameAuthorName;
    /** 源码定位行最后修改 Author 时间。 */
    private LocalDateTime blameAuthorTime;
    private String judgement;
    private String severity;
    private String aiCategory;
    private String resultTitle;
    private String resultSummary;
    private String analysisBasis;
    private String rootCause;
    private String impactDescription;
    private String recommendation;
    private Integer suggestedResolutionDays;
    private String verification;
    private String uncertainty;
    private String ruleSuggestion;
    private BigDecimal confidence;
    private Boolean humanReviewRequired;
    private String resultHash;
    private Long inputTokenCount;
    private Long outputTokenCount;
    private Long totalTokenCount;
    private Long totalLatencyMillis;
    private String lastErrorCode;
    private String lastErrorMessage;
    /** 最近一次显式重跑操作 ID；用于固定范围和隔离普通租约恢复。 */
    private Long rerunOperationId;
    /** 最近一次显式重跑子状态：WAITING/RUNNING/SUCCESS/FAILED。 */
    private String rerunStatus;
    /** 当前显式重跑操作执行令牌。 */
    private String rerunExecutionToken;
    private LocalDateTime startedTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
