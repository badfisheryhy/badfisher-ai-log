package io.github.badfisher.ailog.loki.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.badfisher.ailog.loki.client.LokiClient;
import io.github.badfisher.ailog.loki.client.LokiResponseParser;
import io.github.badfisher.ailog.loki.datasource.LogDataSource;
import io.github.badfisher.ailog.loki.datasource.LokiLogDataSource;
import io.github.badfisher.ailog.loki.query.DefaultLogQlBuilder;
import io.github.badfisher.ailog.loki.query.LogQlBuilder;

/**
 * Loki 自动装配：仅在 {@code badfisher.loki.enabled=true} 时生效。
 * <p>
 * 装配 LogQL 构建器、响应解析器、HTTP 客户端与日志数据源，并按配置设置连接与读取超时。
 * 启用时必须提供非空 {@code base-url}，否则启动失败。
 */
@Configuration
@EnableConfigurationProperties(LokiProperties.class)
@ConditionalOnProperty(prefix = "badfisher.loki", name = "enabled", havingValue = "true")
public class LokiAutoConfiguration {

    /** 装配 LogQL 构建器。 */
    @Bean
    LogQlBuilder logQlBuilder(LokiProperties properties) {
        return new DefaultLogQlBuilder(properties);
    }

    /** 装配响应解析器，注入 service 标签名。 */
    @Bean
    LokiResponseParser lokiResponseParser(ObjectMapper mapper, LokiProperties properties) {
        return new LokiResponseParser(mapper, properties.getLabels().getService());
    }

    /** 装配 Loki HTTP 客户端，并按配置设置超时与 Basic Auth。 */
    @Bean
    LokiClient lokiClient(LokiProperties properties, LokiResponseParser parser) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().trim().isEmpty()) {
            throw new IllegalStateException("Loki base-url is required when enabled");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectSeconds() * 1000);
        factory.setReadTimeout(properties.getReadSeconds() * 1000);
        RestTemplate restTemplate = new RestTemplate(factory);
        if (properties.getUsername() != null && !properties.getUsername().trim().isEmpty()) {
            if (properties.getPassword() == null || properties.getPassword().isEmpty()) {
                throw new IllegalStateException("Loki password is required when username is configured");
            }
            restTemplate.getInterceptors().add(new BasicAuthenticationInterceptor(
                    properties.getUsername(), properties.getPassword()));
        }
        return new LokiClient(restTemplate, properties.getBaseUrl(), parser,
                properties.getRetryMaxAttempts(), properties.getRetryBackoffMillis(),
                properties.getMaxResponseBytes());
    }

    /** 装配日志数据源。 */
    @Bean
    LogDataSource logDataSource(LokiClient client, LogQlBuilder builder) {
        return new LokiLogDataSource(client, builder);
    }
}
