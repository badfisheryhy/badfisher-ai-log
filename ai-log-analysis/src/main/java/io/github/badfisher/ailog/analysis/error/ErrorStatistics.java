package io.github.badfisher.ailog.analysis.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;

/**
 * ERROR 日志聚合统计结果。
 */
@Getter
public final class ErrorStatistics {

    /** 日志总条数。 */
    private final long totalCount;
    /** 识别出 Java 异常的条数。 */
    private final long javaExceptionCount;
    /** 解析出出错来源的条数。 */
    private final long parsedSourceCount;
    /** 分类名称到条数的映射。 */
    private final Map<String, Long> categoryCounts;
    /** 来源类名到条数的映射。 */
    private final Map<String, Long> sourceClassCounts;
    /** 来源方法名到条数的映射。 */
    private final Map<String, Long> sourceMethodCounts;
    /** 异常类名到条数的映射。 */
    private final Map<String, Long> exceptionClassCounts;

    /**
     * 构造统计结果。
     *
     * @param total          日志总条数
     * @param exceptionCount 识别出 Java 异常的条数
     * @param sourceCount    解析出出错来源的条数
     * @param categories     分类名称到条数的映射
     * @param sourceClasses  来源类名到条数的映射
     * @param sourceMethods  来源方法名到条数的映射
     * @param exceptions     异常类名到条数的映射
     */
    public ErrorStatistics(long total, long exceptionCount, long sourceCount, Map<String, Long> categories,
            Map<String, Long> sourceClasses, Map<String, Long> sourceMethods, Map<String, Long> exceptions) {
        totalCount = total;
        javaExceptionCount = exceptionCount;
        parsedSourceCount = sourceCount;
        categoryCounts = immutableCopy(categories);
        sourceClassCounts = immutableCopy(sourceClasses);
        sourceMethodCounts = immutableCopy(sourceMethods);
        exceptionClassCounts = immutableCopy(exceptions);
    }

    /**
     * 返回不可变副本，防止外部修改统计结果。
     *
     * @param source 原始映射
     * @return 不可变映射副本
     */
    private static Map<String, Long> immutableCopy(Map<String, Long> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
