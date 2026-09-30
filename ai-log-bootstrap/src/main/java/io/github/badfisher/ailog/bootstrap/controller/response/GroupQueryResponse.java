package io.github.badfisher.ailog.bootstrap.controller.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import io.github.badfisher.ailog.persistence.query.GroupQueryData.EventStats;
import io.github.badfisher.ailog.persistence.query.GroupQueryData.ModuleOption;

/** 治理页面投影；不返回 AI 租约、完整 Evidence JSON 或服务器配置。 */
public final class GroupQueryResponse {
    private GroupQueryResponse() {
    }

    /** 显式分页结果，total 为数据库筛选后的行数。 */
    @Data
    @Schema(name = "GroupQueryResponse.Page")
    public static class Page<T> {
        private long total;
        private int pageNum;
        private int pageSize;
        private List<T> records = Collections.emptyList();
    }

    /** 当前页问题展示数据及按钮提示，写接口仍重新校验权限和版本。 */
    @Data
    @Schema(name = "GroupQueryResponse.Row")
    public static class Row {
        /** 永久问题 ID。 */
        private Long issueGroupId;
        /** 环境编码。 */
        private String environment;
        /** 系统编码。 */
        private String systemCode;
        /** 模块编码。 */
        private String moduleCode;
        /** Group 基础根因分类。 */
        private String rootCauseCategory;
        /** 当前 AI 执行状态，与旧成功结果独立。 */
        private String aiStatus;
        /** 当前有效 AI 指针。 */
        private Long currentAiItemId;
        /** 当前 AI 标题；无有效结果时为空。 */
        private String title;
        /** 当前 AI 摘要。 */
        private String description;
        /** AI 等级。 */
        private String aiSeverity;
        /** AI 分类。 */
        private String aiCategory;
        /** 源码关联作者。 */
        private String blameAuthorName;
        /** 治理问题类型；AI 仅补齐空值，人工可修改。 */
        private String problemType;
        /** 治理问题等级；AI 仅补齐空值，人工可修改。 */
        private String problemLevel;
        /** 审核状态。 */
        private String reviewStatus;
        /** 处理状态。 */
        private String processStatus;
        /** 责任人 ID。 */
        private Integer ownerUserId;
        /** 启用映射中的责任人名称；无映射时为空。 */
        private String ownerName;
        /** 认领人 ID。 */
        private Integer claimUserId;
        /** 认领人名称。 */
        private String claimUserName;
        /** 审核人 ID。 */
        private Integer reviewerUserId;
        /** 审核人名称。 */
        private String reviewerName;
        /** 审核说明。 */
        private String reviewRemark;
        /** 人工结论。 */
        private String humanConclusion;
        /** 人工已审核的结论版本，可能不同于当前 AI 指针。 */
        private Long reviewedAiItemId;
        /** 验收确认人 ID。 */
        private Integer completedUserId;
        /** 验收通过时间。 */
        private LocalDateTime completedTime;
        /** 验收说明，与解决说明分别保存。 */
        private String completionRemark;
        /** 治理乐观锁版本。 */
        private Integer governanceVersion;
        /** 全部 Event 发生次数。 */
        private long occurrenceCount;
        /** 全部 Event 首次发生时间。 */
        private LocalDateTime firstOccurredAt;
        /** 全部 Event 最近发生时间。 */
        private LocalDateTime lastOccurredAt;
        /** 可选次数展示日期。 */
        private LocalDate logDate;
        /** 展示日期的 occurrence，未指定日期时为空。 */
        private Long dateOccurrenceCount;
        /** 待处理且尚未审核通过，不依赖 AI 结论。 */
        private boolean canReview;
        /** 待处理、审核通过且没有责任人。 */
        private boolean canAssign;
        /** 待处理、审核通过且没有责任人和认领人。 */
        private boolean canClaim;
        /** 审核通过、处理中且本人为责任人，不要求主动认领。 */
        private boolean canResolve;
        /** 已解决，可以验收确认处理结果。 */
        private boolean canComplete;
        /** 已解决或已完成。 */
        private boolean canReopen;
        /** 尚未忽略，可由当前操作人忽略。 */
        private boolean canIgnore;
    }

    /** 当前成功 AI 结论的白名单字段。 */
    @Data
    @Schema(name = "GroupQueryResponse.AiResult")
    public static class AiResult {
        /** 当前成功 AI Item ID。 */
        private Long id;
        /** 该结论的代表样本 ID。 */
        private Long sampleEventId;
        /** AI 判断。 */
        private String judgement;
        /** AI 原始等级，不等于人工等级。 */
        private String severity;
        /** AI 分类。 */
        private String aiCategory;
        /** AI 标题。 */
        private String resultTitle;
        /** AI 摘要。 */
        private String resultSummary;
        /** 根因分析。 */
        private String rootCause;
        /** 分析依据。 */
        private String analysisBasis;
        /** 影响说明。 */
        private String impactDescription;
        /** 处理建议。 */
        private String recommendation;
        /** AI 建议解决天数。 */
        private Integer suggestedResolutionDays;
        /** 置信度。 */
        private BigDecimal confidence;
        /** 验证建议。 */
        private String verification;
        /** 不确定性。 */
        private String uncertainty;
        /** 源码作者映射后的真实姓名；未命中用启用的默认人员，无默认人员时为空。 */
        private String blameAuthorName;
        /** 分析完成时间。 */
        private LocalDateTime finishTime;
    }

    /** 详情明确区分身份、AI、治理和事实四部分。 */
    @Data
    @Schema(name = "GroupQueryResponse.Detail")
    public static class Detail {
        private IssueGroupView group;
        private AiResult ai;
        private GroupGovernanceView governance;
        private EventStats eventSummary;
    }

    /** 有界日志证据展示。 */
    @Data
    @Schema(name = "GroupQueryResponse.Event")
    public static class Event {
        /** Event ID。 */
        private Long id;
        /** Group ID。 */
        private Long issueGroupId;
        /** 解析任务 ID。 */
        private Long analysisTaskId;
        /** 来源文件 ID。 */
        private Long fileRecordId;
        /** 发生业务日期。 */
        private LocalDate logDate;
        /** 该聚合 Event 的次数。 */
        private Long occurrenceCount;
        /** 首次发生时间。 */
        private LocalDateTime firstSeenTime;
        /** 最近发生时间。 */
        private LocalDateTime lastSeenTime;
        /** 脱敏后的代表样本。 */
        private String sampleContent;
        /** 样本是否截断。 */
        private Boolean sampleContentTruncated;
        /** 异常类型。 */
        private String exceptionClass;
        /** 脱敏异常消息。 */
        private String exceptionMessage;
        /** 根因异常类型。 */
        private String rootCauseException;
        /** 脱敏根因消息。 */
        private String rootCauseMessage;
        /** 归一化消息。 */
        private String normalizedMessage;
        /** 脱敏调用栈。 */
        private String simplifiedStack;
        /** 链路 ID。 */
        private String traceId;
        /** 线程上下文 ID。 */
        private String tid;
        /** 请求 ID。 */
        private String requestId;
        /** 分类原因。 */
        private String reasonCode;
        /** 分类快照。 */
        private String rootCauseCategory;
        /** 命中规则 ID。 */
        private Long matchedRuleId;
        /** 是否预期异常。 */
        private Boolean expected;
        /** 是否需要 AI。 */
        private Boolean aiRequired;
    }

    /** 当前页面专用选项；类型和等级使用人工治理与 AI 共用的固定枚举。 */
    @Data
    @Schema(name = "GroupQueryResponse.Options")
    public static class Options {
        private List<ModuleOption> modules;
        /** 筛选类型，包含 UNKNOWN；显示 label，提交 value。 */
        private List<ManagementOptionResponse> problemTypes;
        /** 人工修改允许的明确问题类型，不含 UNKNOWN；显示 label，提交 value。 */
        private List<ManagementOptionResponse> manualProblemTypes;
        /** 问题等级，显示 label，提交 value。 */
        private List<ManagementOptionResponse> problemLevels;
        private List<ManagementOptionResponse> reviewStatuses;
        private List<ManagementOptionResponse> processStatuses;
        private List<ManagementOptionResponse> users;
    }
}
