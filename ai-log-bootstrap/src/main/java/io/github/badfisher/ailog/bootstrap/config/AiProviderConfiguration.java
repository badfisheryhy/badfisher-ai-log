package io.github.badfisher.ailog.bootstrap.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.badfisher.ailog.bootstrap.integration.ai.FunctionCallingAnalysisProperties;
import io.github.badfisher.ailog.bootstrap.integration.ai.OpenAiAnalysisProvider;
import io.github.badfisher.ailog.bootstrap.integration.ai.OpenAiFunctionCallingAnalysisProvider;
import io.github.badfisher.ailog.bootstrap.integration.ai.OpenAiResponsesClient;
import io.github.badfisher.ailog.bootstrap.integration.ai.ResponsesClient;
import io.github.badfisher.ailog.bootstrap.integration.ai.deepseek.DeepSeekResponsesClient;
import io.github.badfisher.ailog.bootstrap.integration.git.GitWorkspacePathResolver;
import io.github.badfisher.ailog.domain.ai.AiAnalysisProvider;
import io.github.badfisher.ailog.domain.ai.AiAnalysisProviderRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates isolated HTTP clients for enabled provider/model routes. */
@Configuration
@EnableConfigurationProperties(AiAnalysisProperties.class)
@ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
public class AiProviderConfiguration {

    @Bean
    public AiAnalysisProviderRegistry aiAnalysisProviderRegistry(AiAnalysisProperties properties,
            ObjectMapper mapper, FunctionCallingAnalysisProperties tools,
            ObjectProvider<GitWorkspacePathResolver> workspaceProvider) {
        Map<String, AiAnalysisProvider> routes = new LinkedHashMap<>();
        for (Map.Entry<String, AiAnalysisProperties.ProviderProperties> provider
                : properties.getProviders().entrySet()) {
            if (!provider.getValue().isEnabled()) {
                continue;
            }
            ResponsesClient client = switch (provider.getValue().getApiType()) {
                case OPENAI_RESPONSES -> new OpenAiResponsesClient(mapper, provider.getValue());
                case DEEPSEEK_RESPONSES -> new DeepSeekResponsesClient(mapper, provider.getValue());
            };
            for (Map.Entry<String, AiAnalysisProperties.ModelProperties> model
                    : provider.getValue().getModels().entrySet()) {
                if (!model.getValue().isEnabled()) {
                    continue;
                }
                AiAnalysisProvider adapter;
                if (tools.isEnabled()) {
                    GitWorkspacePathResolver workspace = workspaceProvider.getIfAvailable();
                    if (workspace == null) {
                        throw new IllegalStateException("Source analysis requires a configured source workspace");
                    }
                    adapter = new OpenAiFunctionCallingAnalysisProvider(client, mapper, tools,
                            workspace, provider.getKey(), model.getKey());
                } else {
                    adapter = new OpenAiAnalysisProvider(client, mapper, provider.getKey(), model.getKey());
                }
                routes.put(AiAnalysisProviderRegistry.routeKey(provider.getKey(), model.getKey()), adapter);
            }
        }
        return new AiAnalysisProviderRegistry(routes,
                properties.getDefaultProvider(), properties.getDefaultModel());
    }
}