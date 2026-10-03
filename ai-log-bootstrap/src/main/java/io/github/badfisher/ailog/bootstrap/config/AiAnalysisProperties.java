package io.github.badfisher.ailog.bootstrap.config;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.badfisher.ailog.domain.ai.AiAnalysisContract;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Setter;

/**
 * AI 分析配置。
 *
 * <p>供应商配置负责连接、鉴权和重试参数，模型配置负责模型名称及模型级请求参数。
 * {@code defaultProvider} 与 {@code defaultModel} 指定默认路由；每个供应商使用独立 HTTP 客户端。
 * 未单独配置默认模型参数时采用 ModelProperties 默认值。</p>
 */
@Getter
@ConfigurationProperties(prefix = "badfisher.ai")
@Setter
public class AiAnalysisProperties implements org.springframework.beans.factory.InitializingBean {

    private boolean enabled;
    private String defaultProvider = "";
    private String defaultModel = "";
    private String promptVersion = AiAnalysisContract.PROMPT_VERSION;
    private String sanitizerVersion = "sanitizer-v1";
    private String modelProfileCode = "default";
    private int selectionLimit = 200;
    /** 每个 AI 任务的 Item 查询页大小；任务 Group 上限由 selectionLimit 控制。 */
    private int dispatchLimit = 50;
    private int sampleLimit = 3;
    private int workerThreads = 5;
    private int queueCapacity = 50;
    private int leaseSeconds = 180;
    private Map<String, ProviderProperties> providers = new LinkedHashMap<String, ProviderProperties>();

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        ProviderProperties provider = providers.get(defaultProvider);
        if (provider == null || !provider.isEnabled() || defaultModel == null || defaultModel.isBlank()) {
            throw new IllegalStateException("Enabled AI requires default-provider and default-model configuration");
        }
        provider.getModels().computeIfAbsent(defaultModel, ignored -> new ModelProperties());
    }

    public enum ApiType {
        OPENAI_RESPONSES,
        DEEPSEEK_RESPONSES
    }

    /** 单个 AI 供应商的连接配置，API Key 不得写入日志。 */
    @Getter
    @Setter
    public static class ProviderProperties {

        private boolean enabled = true;
        /** 默认保留既有 OpenAI 传输；DeepSeek 必须显式选用独立实现。 */
        private ApiType apiType = ApiType.OPENAI_RESPONSES;
        private String baseUrl = "";
        private String apiKey = "";
        /** 仅在显式启用时允许明文 HTTP；不影响 HTTPS 证书校验。 */
        private boolean allowInsecureHttp;
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 60;
        private int maxAttempts = 3;
        private long retryBackoffMillis = 1000L;
        private Map<String, ModelProperties> models = new LinkedHashMap<String, ModelProperties>();

    }

    /**
     * 单个模型的请求配置。Map 的键即发送给供应商的模型名称。
     */
    @Getter
    @Setter
    public static class ModelProperties {

        private boolean enabled = true;
        private int maxTokens = 2000;
        /** 仅由 DeepSeek 适配器读取；空值采用上游默认，OpenAI 不发送此配置。 */
        private String reasoningEffort;

    }
}
