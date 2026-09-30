package io.github.badfisher.ailog.domain.log;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import lombok.Getter;

/**
 * 来自 Loki 的一条原始日志条目，保持原始内容不做任何改写。
 * <p>
 * {@code labels} 以不可变视图暴露，内部做防御性拷贝，保证条目构造后状态不可变。
 */
@Getter
public final class RawLogEntry {

    /** 日志时间戳（UTC，来自 Loki 纳秒精度）。 */
    private final Instant timestamp;

    /** Loki stream 标签，不可变。 */
    private final Map<String, String> labels;

    /** 原始日志行内容。 */
    private final String line;

    /** 日志定位引用。 */
    private final LogReference reference;

    /**
     * 构造原始日志条目。
     *
     * @param timestamp 时间戳，非空
     * @param labels    标签映射，{@code null} 视为空映射；内部做防御性拷贝并包装为不可变
     * @param line      日志行，非空
     * @param reference 日志定位引用，非空
     */
    public RawLogEntry(Instant timestamp, Map<String, String> labels, String line, LogReference reference) {
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.labels = Collections.unmodifiableMap(
                new HashMap<String, String>(labels == null ? Collections.<String, String>emptyMap() : labels));
        this.line = Objects.requireNonNull(line, "line");
        this.reference = Objects.requireNonNull(reference, "reference");
    }
}
