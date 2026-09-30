package io.github.badfisher.ailog.bootstrap.controller.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import io.github.badfisher.ailog.persistence.query.DashboardQueryData.ModuleKey;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Dashboard 展示数据；各面板的日期事实来自 Event，治理状态均为当前状态。 */
public final class DashboardQueryResponse {
    private DashboardQueryResponse() {
    }

    /** 当前处理状态互斥，计数直接供状态饼图使用。 */
    @Data
    public static class Overview {
        private long totalCount;
        /** 当前待处理 Group 数。 */
        private long pendingCount;
        private long processingCount;
        private long resolvedCount;
        /** 验收通过的已完成问题数。 */
        private long completedCount;
        private long ignoredCount;
    }

    /** 日期数组与每个 series.values 一一对应。 */
    @Data
    public static class ModuleTrend {
        private List<LocalDate> dates = new ArrayList<LocalDate>();
        private List<Series> series = new ArrayList<Series>();
    }

    /** 当前配置没有模块显示名称，返回真实的环境、系统、模块编码。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Series extends ModuleKey {
        /** 每个业务日的 occurrence 之和，不是 Event 行数。 */
        private List<Long> values = new ArrayList<Long>();
    }

    /** 同一份模块数据用于分布、处理对比和审核比例。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ModuleStatistics extends ModuleKey {
        private long groupCount;
        private long resolvedCount;
        /** 验收通过的已完成问题数。 */
        private long completedCount;
        /** 审核通过或驳回的问题数。 */
        private long reviewedCount;
        /** 模块内当前已忽略的问题数。 */
        private long ignoredCount;
        /** 百分数，范围 0—100，保留两位小数。 */
        private BigDecimal reviewRate = BigDecimal.ZERO;
    }

    /** 模块按问题数倒序，数量相同按环境/系统/模块编码升序。 */
    @Data
    public static class Modules {
        private List<ModuleStatistics> modules = new ArrayList<ModuleStatistics>();
        /** 从 modules 前五项截取，不再次访问数据库。 */
        private List<ModuleStatistics> topModules = new ArrayList<ModuleStatistics>();
    }
}
