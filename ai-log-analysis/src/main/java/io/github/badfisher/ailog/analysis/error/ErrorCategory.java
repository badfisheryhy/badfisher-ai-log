package io.github.badfisher.ailog.analysis.error;

/**
 * ERROR 日志业务分类。
 * <p>
 * 枚举代码用于接口和持久化，展示名称用于中文报表。代码一旦对外使用不得随意修改。
 */
public enum ErrorCategory {

    /** 库存不足/自动配货失败。 */
    INVENTORY_ALLOCATION("库存不足/自动配货失败"),
    /** 订单履约失败。 */
    ORDER_FULFILLMENT("订单履约失败"),
    /** 退款单处理失败。 */
    REFUND_ORDER("退款单处理失败"),
    /** 外部平台鉴权失败。 */
    EXTERNAL_AUTH("外部平台鉴权失败"),
    /** 订单不存在。 */
    ORDER_NOT_FOUND("订单不存在"),
    /** 付款信息同步失败。 */
    PAYMENT_SYNC("付款信息同步失败"),
    /** 入库/仓库配置错误。 */
    WAREHOUSE_CONFIG("入库/仓库配置错误"),
    /** 交货单处理失败。 */
    DELIVERY_ORDER("交货单处理失败"),
    /** 外部服务/API调用失败。 */
    EXTERNAL_API("外部服务/API调用失败"),
    /** 超时。 */
    TIMEOUT("超时"),
    /** 数据库/SQL。 */
    DATABASE_SQL("数据库/SQL"),
    /** MQ消费处理失败。 */
    MESSAGE_QUEUE("MQ消费处理失败"),
    /** Java异常。 */
    JAVA_EXCEPTION("Java异常"),
    /** 其他业务ERROR。 */
    OTHER_BUSINESS_ERROR("其他业务ERROR");

    /** 展示名称，用于中文报表。 */
    private final String displayName;

    /**
     * 构造枚举项。
     *
     * @param name 中文展示名称
     */
    ErrorCategory(String name) {
        displayName = name;
    }

    /**
     * 返回中文展示名称。
     *
     * @return 中文展示名称
     */
    public String getDisplayName() {
        return displayName;
    }
}
