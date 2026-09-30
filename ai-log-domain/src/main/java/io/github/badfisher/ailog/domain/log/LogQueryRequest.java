package io.github.badfisher.ailog.domain.log;

import java.time.Instant;
import java.util.Objects;

import lombok.Getter;

/**
 * 一次 Loki 日志查询请求，采用半开区间 {@code [startInclusive, endExclusive)}。
 * <p>
 * 区间语义与设计文档一致：所有时间范围均使用左闭右开，避免相邻窗口重叠或出现缺口。
 */
@Getter
public final class LogQueryRequest {

    /** 查询范围（环境、系统、服务）。 */
    private final LogSearchCondition condition;

    /** 窗口起点（含）。 */
    private final Instant startInclusive;

    /** 窗口终点（不含）。 */
    private final Instant endExclusive;

    /** 单次查询返回上限。 */
    private final int limit;

    /**
     * 构造查询请求。
     *
     * @param condition      查询范围，非空
     * @param startInclusive 窗口起点（含），非空
     * @param endExclusive   窗口终点（不含），非空且必须晚于起点
     * @param limit          返回上限，必须为正
     */
    public LogQueryRequest(LogSearchCondition condition, Instant startInclusive, Instant endExclusive, int limit) {
        this.condition = Objects.requireNonNull(condition, "condition");
        this.startInclusive = Objects.requireNonNull(startInclusive, "start");
        this.endExclusive = Objects.requireNonNull(endExclusive, "end");
        if (!startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("start must be before end");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }
}
