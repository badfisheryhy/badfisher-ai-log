package io.github.badfisher.ailog.analysis.module;

import io.github.badfisher.ailog.analysis.error.ErrorStatistics;
import io.github.badfisher.ailog.domain.log.RawLogEntry;

/**
 * ERROR 统计增量收集器：逐条消费日志条目，结束时输出聚合统计。
 * <p>
 * 内存上界由不同分类键的数量决定，与输入日志总量无关，供全文流式分析链路使用。
 */
public interface ErrorStatisticsCollector {

    /**
     * 累积一条日志条目。
     *
     * @param entry 待统计的日志条目
     */
    void accept(RawLogEntry entry);

    /**
     * 结束累积并输出聚合统计结果。
     *
     * @return 聚合统计结果
     */
    ErrorStatistics build();
}
