package io.github.badfisher.ailog.analysis.module;

import io.github.badfisher.ailog.analysis.error.ErrorLogStatisticsService;

/** 默认规则分析模块，可供尚未定制规则的项目复用。 */
public final class DefaultErrorAnalysisModule implements ProjectErrorAnalysisModule {

    /** 模块编码，用于配置引用。 */
    public static final String CODE = "default";

    /** 统计服务委托。 */
    private final ErrorLogStatisticsService statisticsService;

    /**
     * 构造默认分析模块。
     *
     * @param statisticsService 统计服务
     */
    public DefaultErrorAnalysisModule(ErrorLogStatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    /**
     * 返回模块唯一编码。
     *
     * @return 模块编码
     */
    @Override
    public String moduleCode() {
        return CODE;
    }

    /**
     * 创建流式统计收集器，委托统计服务逐条累积。
     *
     * @return 新的统计收集器
     */
    @Override
    public ErrorStatisticsCollector newCollector() {
        return statisticsService.newCollector();
    }
}
