package io.github.badfisher.ailog.analysis.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.badfisher.ailog.analysis.module.ErrorStatisticsCollector;
import io.github.badfisher.ailog.domain.log.RawLogEntry;

/**
 * ERROR 日志分类与聚合服务。
 */
public final class ErrorLogStatisticsService {

    /** 确定性分类器。 */
    private final ErrorLogClassifier classifier;

    /**
     * 构造统计服务。
     *
     * @param errorLogClassifier 确定性分类器
     */
    public ErrorLogStatisticsService(ErrorLogClassifier errorLogClassifier) {
        classifier = errorLogClassifier;
    }

    /**
     * 对一组日志条目执行分类并聚合统计。
     *
     * @param entries 待统计的 ERROR 日志条目
     * @return 聚合统计结果
     */
    public ErrorStatistics summarize(List<RawLogEntry> entries) {
        ErrorStatisticsCollector collector = newCollector();
        for (RawLogEntry entry : entries) {
            collector.accept(entry);
        }
        return collector.build();
    }

    /**
     * 创建增量统计收集器，供流式逐条累积场景使用。
     *
     * @return 新的统计收集器
     */
    public ErrorStatisticsCollector newCollector() {
        return new StatisticsCollector();
    }

    /** 逐条累积分类计数的收集器实现。 */
    private final class StatisticsCollector implements ErrorStatisticsCollector {

        /** 分类名称到条数的计数映射。 */
        private final Map<String, Long> categories = new LinkedHashMap<String, Long>();

        /** 来源类名到条数的计数映射。 */
        private final Map<String, Long> sourceClasses = new LinkedHashMap<String, Long>();

        /** 来源方法名到条数的计数映射。 */
        private final Map<String, Long> sourceMethods = new LinkedHashMap<String, Long>();

        /** 异常类名到条数的计数映射。 */
        private final Map<String, Long> exceptions = new LinkedHashMap<String, Long>();

        /** 已接受条目总数。 */
        private long totalCount = 0L;

        /** 识别出 Java 异常的条数。 */
        private long exceptionCount = 0L;

        /** 解析出出错来源的条数。 */
        private long sourceCount = 0L;

        @Override
        public void accept(RawLogEntry entry) {
            totalCount++;
            ErrorLogClassification result = classifier.classify(entry);
            increment(categories, result.getCategory().getDisplayName());
            if (result.getSourceClass() != null) {
                sourceCount++;
                increment(sourceClasses, result.getSourceClass());
                increment(sourceMethods, result.getSourceMethod());
            }
            if (result.getExceptionClass() != null) {
                exceptionCount++;
                increment(exceptions, result.getExceptionClass());
            }
        }

        @Override
        public ErrorStatistics build() {
            return new ErrorStatistics(totalCount, exceptionCount, sourceCount, sort(categories),
                    sort(sourceClasses), sort(sourceMethods), sort(exceptions));
        }
    }

    /**
     * 统计值累加，不存在时初始化为 1。
     *
     * @param counts 计数映射
     * @param key    计数键
     */
    private static void increment(Map<String, Long> counts, String key) {
        Long current = counts.get(key);
        counts.put(key, current == null ? 1L : current + 1L);
    }

    /**
     * 按计数值降序、键名升序返回排序后的映射。
     *
     * @param counts 待排序的计数映射
     * @return 排序后的新映射
     */
    private static Map<String, Long> sort(Map<String, Long> counts) {
        List<Map.Entry<String, Long>> entries = new ArrayList<Map.Entry<String, Long>>(counts.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> left, Map.Entry<String, Long> right) {
                int byCount = right.getValue().compareTo(left.getValue());
                return byCount != 0 ? byCount : left.getKey().compareTo(right.getKey());
            }
        });
        Map<String, Long> sorted = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, Long> entry : entries) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }
}
