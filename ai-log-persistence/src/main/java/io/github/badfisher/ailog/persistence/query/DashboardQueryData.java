package io.github.badfisher.ailog.persistence.query;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Dashboard 查询投影，不携带日志样本或 AI 原文。 */
public final class DashboardQueryData {
    private DashboardQueryData() {
    }

    /** 当前问题状态的 SQL 汇总，缺失治理记录不得静默漏算。 */
    @Data
    public static class Overview {
        private long totalCount;
        private long pendingCount;
        private long processingCount;
        private long resolvedCount;
        /** 验收通过的已完成问题数。 */
        private long completedCount;
        private long ignoredCount;
        private long missingGovernanceCount;
    }

    /** 总览、趋势和模块统计共用的业务日期和模块范围。 */
    @Data
    public static class Filter {
        private LocalDate startDate;
        private LocalDate endDate;
        private String environment;
        private String systemCode;
        private String moduleCode;
        /** Dashboard 使用当前启用模块范围；日报沿用原有 Event 范围。 */
        private boolean enabledModulesOnly;
    }

    /** 模块完整身份，环境不同的同名模块不能混算。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModuleKey {
        private String environment;
        private String systemCode;
        private String moduleCode;
    }

    /** 日期范围内出现过的 Group 与模块对应关系。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class GroupModuleRef extends ModuleKey {
        private Long issueGroupId;
    }

    /** Event 单表按日、完整模块身份汇总的真实发生次数。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class DailyOccurrence extends ModuleKey {
        private LocalDate logDate;
        private long occurrenceCount;
    }
}
