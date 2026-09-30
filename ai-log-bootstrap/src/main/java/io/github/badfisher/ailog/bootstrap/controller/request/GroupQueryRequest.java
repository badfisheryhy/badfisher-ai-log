package io.github.badfisher.ailog.bootstrap.controller.request;

import java.time.LocalDate;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 问题治理页面查询契约；日期按 Event.log_date 筛选，不表示历史治理快照。 */
public final class GroupQueryRequest {
    private GroupQueryRequest() {
    }

    /** 列表与 summary 使用同一组筛选条件。 */
    @Data
    @Schema(name = "GroupQueryRequest.Filter")
    public static class Filter {
        /** 环境编码。 */
        @Size(max = 32)
        private String environment;
        /** 系统编码。 */
        @Size(max = 64)
        private String systemCode;
        /** 模块编码。 */
        @Size(max = 128)
        private String moduleCode;
        /** 人工问题类型。 */
        @Size(max = 32)
        private String problemType;
        /** 人工问题等级。 */
        @Size(max = 32)
        private String problemLevel;
        /** 审核状态。 */
        @Size(max = 32)
        @Pattern(regexp = "PENDING|APPROVED|REJECTED")
        private String reviewStatus;
        /** 处理状态。 */
        @Size(max = 32)
        @Pattern(regexp = "PENDING|PROCESSING|RESOLVED|COMPLETED|IGNORED")
        private String processStatus;
        /** 责任人 ID。 */
        @Min(1)
        private Integer ownerUserId;
        /** 审核人 ID。 */
        @Min(1)
        private Integer reviewerUserId;
        /** 出现业务日期起始，包含边界。 */
        private LocalDate startLogDate;
        /** 出现业务日期结束，包含边界。 */
        private LocalDate endLogDate;
        /** 当前成功 AI 标题或摘要的字面子串。 */
        @Size(max = 200)
        private String keyword;

        /** 日期范围包含两端；允许只提供起始或结束日期。 */
        @AssertTrue(message = "起始日志日期不能晚于结束日期")
        public boolean isDateRangeValid() {
            return startLogDate == null || endLogDate == null || !startLogDate.isAfter(endLogDate);
        }
    }

    /** 固定按 Group ID 倒序分页；logDate 仅控制次数展示，不影响筛选。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(name = "GroupQueryRequest.Page")
    public static class Page extends Filter {
        @NotNull
        @Min(1)
        private Integer pageNum = 1;
        @NotNull
        @Min(1)
        @Max(100)
        private Integer pageSize = 20;
        /** 可选展示日期；未传时不推断“今天”或“昨天”。 */
        private LocalDate logDate;
    }

    /** 问题身份查询。 */
    @Data
    @Schema(name = "GroupQueryRequest.Detail")
    public static class Detail {
        @NotNull
        @Min(1)
        private Long issueGroupId;
    }

    /** 一个问题的 Event 证据分页，按 log_date、ID 倒序。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(name = "GroupQueryRequest.Events")
    public static class Events extends Detail {
        @NotNull
        @Min(1)
        private Integer pageNum = 1;
        @NotNull
        @Min(1)
        @Max(100)
        private Integer pageSize = 20;
    }
}
