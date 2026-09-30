package io.github.badfisher.ailog.loki.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Loki 数据源配置，绑定 {@code badfisher.loki} 前缀。
 * <p>
 * 默认禁用（{@code enabled=false}），工程不保存生产地址或凭证。启用时必须提供 {@code base-url}。
 */
@Getter
@Setter
@ConfigurationProperties("badfisher.loki")
public class LokiProperties {

    /** ERROR 级别的查询方式：标签匹配、行内容匹配或 JSON 字段匹配。 */
    public enum LevelQueryMode {
        /** 通过 level 标签匹配。 */
        LABEL,
        /** 通过 JSON 字段匹配。 */
        CONTENT,
        /** 通过 LogQL json 管道匹配。 */
        JSON
    }

    /** 是否启用 Loki 数据源。 */
    private boolean enabled;

    /** Loki 基地址，启用时必填。 */
    private String baseUrl = "";

    /** HTTP Basic Auth 用户名；为空时不发送认证头。 */
    private String username = "";

    /** HTTP Basic Auth 密码；必须通过环境变量或 Secret 注入。 */
    private String password = "";

    /** 连接超时（秒）。 */
    private int connectSeconds = 5;

    /** 读取超时（秒）。 */
    private int readSeconds = 30;

    /** 窗口时长（分钟）。 */
    private int windowMinutes = 15;

    /** 单次查询返回上限。 */
    private int limit = 5000;

    /** 最大重试次数。 */
    private int retryMaxAttempts = 3;

    /** 重试线性退避基准（毫秒）。 */
    private long retryBackoffMillis = 500L;

    /** 响应体大小硬上限（字节），防止异常大响应耗尽内存。 */
    private long maxResponseBytes = 50 * 1024 * 1024L;

    /** ERROR 级别查询模式。 */
    private LevelQueryMode levelQueryMode = LevelQueryMode.LABEL;

    /** ERROR 级别文本。 */
    private String errorText = "ERROR";

    /** 标签名映射配置。 */
    private final Labels labels = new Labels();

    /**
     * Loki 标签名配置，将平台维度映射到实际的 Loki 标签。
     */
    @Getter
    @Setter
    public static class Labels {

        /** 环境标签名。 */
        private String environment = "env";

        /** 系统标签名。 */
        private String system = "system";

        /** 服务标签名。 */
        private String service = "service";

        /** 级别标签名。 */
        private String level = "level";
    }
}
