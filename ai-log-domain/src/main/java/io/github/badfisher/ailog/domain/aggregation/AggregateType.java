package io.github.badfisher.ailog.domain.aggregation;

/** 文件级错误聚合类型。 */
public enum AggregateType {
    /** expected BUSINESS 按业务编码聚合。 */
    BUSINESS,
    /** 其他异常按稳定指纹聚合。 */
    ISSUE
}
