package io.github.badfisher.ailog.persistence.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Group 页面只读查询参数和轻量投影，不改变领域实体。 */
public final class GroupQueryData {
    private GroupQueryData() {
    }

    /** 分页和计数共用的数据库筛选条件。 */
    @Data
    @Schema(name = "GroupQueryData.Filter")
    public static class Filter {
        /** 环境编码。 */
        private String environment;
        /** 系统编码。 */
        private String systemCode;
        /** 模块编码。 */
        private String moduleCode;
        /** 人工问题类型。 */
        private String problemType;
        /** 人工问题等级。 */
        private String problemLevel;
        /** 审核状态。 */
        private String reviewStatus;
        /** 处理状态。 */
        private String processStatus;
        /** 责任人 ID。 */
        private Integer ownerUserId;
        /** 工作台范围：未指派或由此用户负责；仅由服务端登录身份赋值。 */
        private Integer workbenchUserId;
        /** 审核人 ID。 */
        private Integer reviewerUserId;
        /** 出现业务日期起始，包含边界。 */
        private LocalDate startLogDate;
        /** 出现业务日期结束，包含边界。 */
        private LocalDate endLogDate;
        /** 当前成功 AI 标题或摘要的字面子串。 */
        private String keyword;
    }

    /** 相同筛选范围内的当前治理状态统计；各审核/处理维度不要求相加等于 total。 */
    @Data
    @Schema(name = "GroupQueryData.Summary")
    public static class Summary {
        /** 问题总数。 */
        private long total;
        /** PENDING 数量。 */
        private long pendingReview;
        /** 当前筛选范围内审核拒绝（REJECTED）的案件数。 */
        private long rejectedReview;
        /**
         * AI 识别准确率：(1 - rejectedReview / total) × 100，包含待审核案件。
         * 百分数保留两位小数；没有案件时返回 100。
         */
        private BigDecimal aiRecognitionAccuracy = BigDecimal.valueOf(100).setScale(2);
        /** 审核通过、未指派且无责任人的数量。 */
        private long pendingAssign;
        /** PROCESSING 数量。 */
        private long processing;
        /** RESOLVED 数量。 */
        private long resolved;
        /** COMPLETED 数量。 */
        private long completed;
        /** IGNORED 数量。 */
        private long ignored;
    }

    /** 当前页 Group 的全历史 Event 概况及可选业务日期次数。 */
    @Data
    @Schema(name = "GroupQueryData.EventStats")
    public static class EventStats {
        /** 问题 ID。 */
        private Long issueGroupId;
        /** 全部已持久化 Event 的累计发生次数。 */
        private long occurrenceCount;
        /** 全部 Event 首次发生时间。 */
        private LocalDateTime firstOccurredAt;
        /** 全部 Event 最近发生时间。 */
        private LocalDateTime lastOccurredAt;
        /** 指定业务日期次数；未指定日期时为空。 */
        private Long dateOccurrenceCount;
    }

    /** 模块级联选项，仅暴露编码，不返回服务器或凭据配置。 */
    @Data
    @Schema(name = "GroupQueryData.ModuleOption")
    public static class ModuleOption {
        /** 环境编码。 */
        private String environment;
        /** 系统编码。 */
        private String systemCode;
        /** 模块编码。 */
        private String moduleCode;
    }
}
