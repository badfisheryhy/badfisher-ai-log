package io.github.badfisher.ailog.common.time;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 将一个自然日按固定时长切分为半开时间窗口的工具类。
 * <p>
 * 默认按 {@code Asia/Shanghai} 时区对前一自然日切分：以当日 00:00 为起点、次日 00:00 为终点，
 * 生成 96 个 15 分钟窗口。窗口起点为闭区间、终点为开区间，相邻窗口首尾衔接且不重叠。
 */
public final class TimeWindowSplitter {

    private TimeWindowSplitter() {
    }

    /**
     * 按指定时长切分某日（指定时区下）的完整自然日。
     *
     * @param date 自然日日期，非空
     * @param size 窗口时长，必须为正
     * @param zone 时区，非空
     * @return 不可变的时间窗口列表，按时间升序排列
     */
    public static List<TimeWindow> split(LocalDate date, Duration size, ZoneId zone) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(zone, "zone");
        if (size.isZero() || size.isNegative()) {
            throw new IllegalArgumentException("windowSize must be positive");
        }
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        Instant cursor = date.atStartOfDay(zone).toInstant();
        List<TimeWindow> result = new ArrayList<>();
        while (cursor.isBefore(end)) {
            Instant next = cursor.plus(size);
            if (next.isAfter(end)) {
                next = end;
            }
            result.add(new TimeWindow(cursor, next));
            cursor = next;
        }
        return Collections.unmodifiableList(result);
    }
}
