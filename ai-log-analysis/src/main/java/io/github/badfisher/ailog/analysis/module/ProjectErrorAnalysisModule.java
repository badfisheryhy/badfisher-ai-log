package io.github.badfisher.ailog.analysis.module;

import java.util.List;

import io.github.badfisher.ailog.analysis.error.ErrorStatistics;
import io.github.badfisher.ailog.domain.log.RawLogEntry;

/** 项目级ERROR分析扩展点；不同项目可以提供独立实现。 */
public interface ProjectErrorAnalysisModule {

    /**
     * 返回模块唯一编码。
     *
     * @return 模块编码，不能为空
     */
    String moduleCode();

    /**
     * 创建流式统计收集器，供逐条消费 ERROR 日志的聚合场景使用。
     *
     * @return 新的统计收集器
     */
    ErrorStatisticsCollector newCollector();

    /**
     * 分析一组 ERROR 日志并返回聚合统计结果。
     *
     * @param entries 待分析的 ERROR 日志条目
     * @return 聚合统计结果
     */
    default ErrorStatistics analyze(List<RawLogEntry> entries) {
        ErrorStatisticsCollector collector = newCollector();
        for (RawLogEntry entry : entries) {
            collector.accept(entry);
        }
        return collector.build();
    }
}
