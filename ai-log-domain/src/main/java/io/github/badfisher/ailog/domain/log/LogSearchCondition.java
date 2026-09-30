package io.github.badfisher.ailog.domain.log;

import java.util.Objects;

import lombok.Getter;

/**
 * 日志查询的范围维度，严格由 environment、system、service 三元组构成。
 * <p>
 * 这是数据隔离的基本单位：不同范围之间的日志不得跨范围聚合或合并。
 */
@Getter
public final class LogSearchCondition {

    /** 环境编码，例如 {@code prod}、{@code pre}。 */
    private final String environment;

    /** 系统编码，例如 {@code middle-platform}。 */
    private final String system;

    /** 服务编码，例如 {@code major}、{@code logistics}。 */
    private final String service;

    /**
     * 构造查询范围，三个维度均不允许为 {@code null} 或空白。
     *
     * @param environment 环境
     * @param system      系统
     * @param service     服务
     */
    public LogSearchCondition(String environment, String system, String service) {
        this.environment = require(environment, "environment");
        this.system = require(system, "system");
        this.service = require(service, "service");
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
