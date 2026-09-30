package io.github.badfisher.ailog.domain.pipeline;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 流水线查询 DTO；显式固定响应字段，不暴露持久化实体或数据库注解。 */
public final class PipelineTaskViews {

    private PipelineTaskViews() {
    }

    /** AnalysisTask 查询快照，字段与当前流水线响应契约保持一致。 */
    @Data
    public static class AnalysisTask {
        private Long id;
        private String taskNo;
        private Long fileRecordId;
        private Long syncTaskId;
        private Long syncModuleTaskId;
        private String environment;
        private String systemCode;
        private String moduleCode;
        private LocalDate logDate;
        private String status;
        private String fingerprintVersion;
        private Long rawEventCount;
        private Long strictErrorCount;
        private Long fallbackErrorCount;
        private Long rejectedEventCount;
        private Long suppressedErrorCount;
        private Long discardedUnknownCount;
        private Long persistedErrorCount;
        private Long issueCount;
        private Integer retryCount;
        private LocalDateTime startTime;
        private LocalDateTime finishTime;
        private LocalDateTime heartbeatTime;
        private String errorMessage;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
    }

    /** AiTask 查询快照，字段与当前流水线响应契约保持一致。 */
    @Data
    public static class AiTask {
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

    /** AiItem 查询快照，字段与当前流水线响应契约保持一致。 */
    @Data
    public static class AiItem {
        private Long id;
        private Long aiTaskId;
        private Long analysisTaskId;
        private Long issueGroupId;
        private Long sampleEventId;
        private Integer selectionOrder;
        private Long occurrenceCountSnapshot;
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
        private String blameAuthorName;
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
        private Long rerunOperationId;
        private String rerunStatus;
        private String rerunExecutionToken;
        private LocalDateTime startedTime;
        private LocalDateTime finishTime;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
    }

    /** AiAttempt 查询快照，字段与当前流水线响应契约保持一致。 */
    @Data
    public static class AiAttempt {
        private Long id;
        private Long aiTaskItemId;
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
}
