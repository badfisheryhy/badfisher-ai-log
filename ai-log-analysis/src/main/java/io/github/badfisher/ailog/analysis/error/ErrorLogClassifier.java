package io.github.badfisher.ailog.analysis.error;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.badfisher.ailog.domain.log.RawLogEntry;

/**
 * ERROR 日志确定性分类器。
 * <p>
 * 优先提取 Java 异常类；无异常类时，根据来源方法和明确业务关键词分类。规则按优先级执行，
 * 不使用模糊的单词 {@code message} 判断 MQ，避免把普通响应报文误分类为消息队列异常。
 */
public final class ErrorLogClassifier {

    /** 来源定位匹配，形如 ERROR com.x.Y(123) - message。 */
    private static final Pattern SOURCE = Pattern.compile(
            "(?s)\\bERROR\\b\\s*([\\w.$]+)\\((\\d+)\\)\\s*-\\s*(.*)$");
    /** 异常类名匹配，形如 com.x.BizException。 */
    private static final Pattern EXCEPTION = Pattern.compile(
            "(?:Caused by:\\s*)?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*(?:Exception|Error))(?=[:\\s]|$)");

    /**
     * 对单条日志条目分类。
     *
     * @param entry 待分类的日志条目
     * @return 确定性分类结果
     */
    public ErrorLogClassification classify(RawLogEntry entry) {
        String line = entry.getLine();
        Matcher sourceMatcher = SOURCE.matcher(line);
        String sourceMethod = null;
        String sourceClass = null;
        String message = line;
        if (sourceMatcher.find()) {
            sourceMethod = sourceMatcher.group(1);
            message = sourceMatcher.group(3);
            int separator = sourceMethod.lastIndexOf('.');
            sourceClass = separator > 0 ? sourceMethod.substring(0, separator) : sourceMethod;
        }

        Matcher exceptionMatcher = EXCEPTION.matcher(line);
        String exceptionClass = exceptionMatcher.find() ? exceptionMatcher.group(1) : null;
        ErrorCategory category = category(sourceMethod, message, exceptionClass);
        return new ErrorLogClassification(category, sourceClass, sourceMethod, exceptionClass);
    }

    /**
     * 按业务关键词、来源位置和异常类名计算分类。
     *
     * @param source        出错来源方法
     * @param message       日志消息
     * @param exceptionClass 异常类名，可能为空
     * @return 业务分类
     */
    private static ErrorCategory category(String source, String message, String exceptionClass) {
        String text = value(message).toLowerCase(Locale.ROOT);
        String location = value(source).toLowerCase(Locale.ROOT);
        if (containsAny(text, "库存不足", "库存锁定失败", "自动配货失败")) {
            return ErrorCategory.INVENTORY_ALLOCATION;
        }
        if (containsAny(text, "履约失败", "fulfill")) {
            return ErrorCategory.ORDER_FULFILLMENT;
        }
        if (containsAny(text, "退款单", "退款")) {
            return ErrorCategory.REFUND_ORDER;
        }
        if (containsAny(text, "forbidden", "unauthorized", "鉴权", "oauth", "token")
                || location.contains("oauth")) {
            return ErrorCategory.EXTERNAL_AUTH;
        }
        if (containsAny(text, "订单不存在", "原订单不存在")) {
            return ErrorCategory.ORDER_NOT_FOUND;
        }
        if (text.contains("付款信息")) {
            return ErrorCategory.PAYMENT_SYNC;
        }
        if (containsAny(text, "入库", "收货仓库")) {
            return ErrorCategory.WAREHOUSE_CONFIG;
        }
        if (containsAny(text, "交货单", "标发失败")) {
            return ErrorCategory.DELIVERY_ORDER;
        }
        if (containsAny(text, "调用异常", "调用失败", "api get error", "接口失败")) {
            return ErrorCategory.EXTERNAL_API;
        }
        if (containsAny(text, "timeout", "timed out", "超时")) {
            return ErrorCategory.TIMEOUT;
        }
        if (containsAny(text, "sql", "database", "jdbc", "mysql", "deadlock", "数据库")) {
            return ErrorCategory.DATABASE_SQL;
        }
        if (location.contains(".mq.") || location.contains("rabbit")
                || containsAny(text, "rabbitmq", "amqp")) {
            return ErrorCategory.MESSAGE_QUEUE;
        }
        if (exceptionClass != null) {
            return ErrorCategory.JAVA_EXCEPTION;
        }
        return ErrorCategory.OTHER_BUSINESS_ERROR;
    }

    /**
     * 判断文本是否包含任一关键字。
     *
     * @param text   待匹配文本
     * @param values 关键字列表
     * @return 命中任一关键字时为 true
     */
    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 空值安全取值。
     *
     * @param value 原始值
     * @return 非空字符串
     */
    private static String value(String value) {
        return value == null ? "" : value;
    }
}
