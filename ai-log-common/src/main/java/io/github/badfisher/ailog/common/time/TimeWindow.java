package io.github.badfisher.ailog.common.time;

import java.time.Instant;
import java.util.Objects;

import lombok.Getter;

/**
 * 半开时间窗口 {@code [startInclusive, endExclusive)}。
 * <p>
 * 窗口语义与设计文档一致：相邻窗口首尾衔接且不重叠。
 */
@Getter
public final class TimeWindow {

    /** 窗口起点（含）。 */
    private final Instant startInclusive;

    /** 窗口终点（不含）。 */
    private final Instant endExclusive;

    /**
     * 构造时间窗口。
     *
     * @param startInclusive 起点（含），非空
     * @param endExclusive   终点（不含），非空且必须晚于起点
     */
    public TimeWindow(Instant startInclusive, Instant endExclusive) {
        this.startInclusive = Objects.requireNonNull(startInclusive, "startInclusive");
        this.endExclusive = Objects.requireNonNull(endExclusive, "endExclusive");
        if (!startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("startInclusive must be before endExclusive");
        }
    }
}
