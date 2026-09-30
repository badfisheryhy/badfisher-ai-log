package io.github.badfisher.ailog.loki.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.badfisher.ailog.domain.log.LogSearchCondition;
import io.github.badfisher.ailog.loki.config.LokiProperties;

/**
 * 默认 LogQL 构建器：按配置的标签名与级别查询模式生成受控查询表达式。
 * <p>
 * 流选择器严格由环境、系统、服务标签构成；ERROR 过滤按 {@link LokiProperties.LevelQueryMode}
 * 分别采用标签匹配、行内容匹配或 JSON 管道匹配。标签名与值均做校验并转义，避免注入。
 */
public final class DefaultLogQlBuilder implements LogQlBuilder {

    /** Loki 配置。 */
    private final LokiProperties properties;

    /**
     * 构造构建器。
     *
     * @param properties Loki 配置，非空
     */
    public DefaultLogQlBuilder(LokiProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public String buildErrorQuery(LogSearchCondition condition) {
        List<String> matchers = scope(condition);
        if (properties.getLevelQueryMode() == LokiProperties.LevelQueryMode.LABEL) {
            add(matchers, properties.getLabels().getLevel(), properties.getErrorText());
            return selector(matchers);
        }
        if (properties.getLevelQueryMode() == LokiProperties.LevelQueryMode.JSON) {
            return selector(matchers) + " | json | level=\"" + escape(properties.getErrorText()) + "\"";
        }
        return selector(matchers) + " |= \"" + escape(properties.getErrorText()) + "\"";
    }

    /** 构造范围标签匹配器列表。 */
    private List<String> scope(LogSearchCondition condition) {
        List<String> matchers = new ArrayList<>();
        add(matchers, properties.getLabels().getEnvironment(), condition.getEnvironment());
        add(matchers, properties.getLabels().getSystem(), condition.getSystem());
        add(matchers, properties.getLabels().getService(), condition.getService());
        return matchers;
    }

    /** 追加一个 {@code name="value"} 匹配器，并校验标签名与值。 */
    private static void add(List<String> matchers, String labelName, String labelValue) {
        if (labelName == null || !labelName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("Invalid Loki label name: " + labelName);
        }
        if (labelValue == null || labelValue.trim().isEmpty()) {
            throw new IllegalArgumentException("Loki matcher value must not be blank");
        }
        matchers.add(labelName + "=\"" + escape(labelValue) + "\"");
    }

    /** 拼接流选择器。 */
    private static String selector(List<String> matchers) {
        return "{" + String.join(", ", matchers) + "}";
    }

    /** 转义 LogQL 字符串值中的特殊字符。 */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
