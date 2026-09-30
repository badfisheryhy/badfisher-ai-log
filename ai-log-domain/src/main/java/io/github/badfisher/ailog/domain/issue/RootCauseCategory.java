package io.github.badfisher.ailog.domain.issue;

/**
 * 错误根因分类。
 * <p>
 * 由 {@code RootCauseClassifier} 依据异常类、消息与调用链特征判定，
 * 用于问题聚合时区分故障类型，指导后续排障方向。
 */
public enum RootCauseCategory {
    /** 业务规则或数据导致的预期内失败，如库存不足、订单状态非法。 */
    BUSINESS,

    /** 代码缺陷，如空指针、非法参数、逻辑错误。 */
    CODE,

    /** 数据库相关故障，如慢 SQL、死锁、连接池耗尽。 */
    DATABASE,

    /** 外部服务（HTTP/RPC/MQ）调用失败或响应异常。 */
    EXTERNAL_SERVICE,

    /** 基础设施故障，如网络、磁盘、内存、宿主机异常。 */
    INFRASTRUCTURE,

    /** 无法判定的根因，保留给人工复核。 */
    UNKNOWN
}
