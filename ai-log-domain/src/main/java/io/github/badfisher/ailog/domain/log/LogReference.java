package io.github.badfisher.ailog.domain.log;

import java.time.Instant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 日志定位引用，记录单条日志的时间戳与所属服务。
 * <p>
 * 用于在聚合、Evidence 与 AI 分析阶段回溯原始日志位置，避免重复保存完整日志内容。
 */
@Getter
@RequiredArgsConstructor
public final class LogReference {

    /** 日志产生时间戳（UTC）。 */
    private final Instant timestamp;

    /** 所属服务编码，来源于 Loki 的 service 标签；无标签时为 {@code null}。 */
    private final String service;
}
