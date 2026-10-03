package io.github.badfisher.ailog.bootstrap.integration.ai.deepseek;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.badfisher.ailog.bootstrap.config.AiAnalysisProperties;
import io.github.badfisher.ailog.bootstrap.config.AiProviderConfiguration;
import io.github.badfisher.ailog.bootstrap.integration.ai.FunctionCallingAnalysisProperties;
import io.github.badfisher.ailog.domain.ai.AiAnalysisContract;
import io.github.badfisher.ailog.domain.ai.AiAnalysisProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderIsolationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AiProviderConfiguration.class)
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withBean(FunctionCallingAnalysisProperties.class, FunctionCallingAnalysisProperties::new);

    @Test
    void deepSeekOnlyStartsWithOpenAiDisabledAndWithoutOpenAiKey() {
        runner.withPropertyValues("badfisher.ai.enabled=true", "badfisher.ai.default-provider=deepseek",
                "badfisher.ai.default-model=deepseek-flash", "badfisher.ai.providers.openai.enabled=false",
                "badfisher.ai.providers.openai.api-key=",
                "badfisher.ai.providers.deepseek.api-type=DEEPSEEK_RESPONSES",
                "badfisher.ai.providers.deepseek.base-url=https://api.deepseek.com",
                "badfisher.ai.providers.deepseek.api-key=synthetic-key",
                "badfisher.ai.providers.deepseek.models.deepseek-flash.reasoning-effort=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AiAnalysisProviderRegistry.class).size()).isEqualTo(1);
                    assertThat(context.getBean(AiAnalysisProperties.class).getProviders().get("deepseek").getApiType())
                            .isEqualTo(AiAnalysisProperties.ApiType.DEEPSEEK_RESPONSES);
                });
    }

    @Test
    void shippedDeepSeekTemplateBindsAndStartsWithoutOpenAiCredentials() {
        String template = Path.of("../config/application-deepseek.example.yml").toAbsolutePath().toUri().toString();
        runner.withPropertyValues("spring.config.additional-location=" + template,
                "badfisher.ai.enabled=true", "badfisher.ai.providers.deepseek.api-key=synthetic-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(AiAnalysisProperties.class);
                    assertThat(properties.getDefaultProvider()).isEqualTo("deepseek");
                    assertThat(properties.getDefaultModel()).isEqualTo("deepseek-flash");
                    assertThat(properties.getProviders().get("openai").isEnabled()).isFalse();
                    var deepseek = properties.getProviders().get("deepseek");
                    assertThat(deepseek.getReadTimeoutSeconds()).isEqualTo(90);
                    assertThat(deepseek.getModels().get("deepseek-flash").getMaxTokens()).isEqualTo(4096);
                    assertThat(deepseek.getModels().get("deepseek-flash").getReasoningEffort()).isEqualTo("none");
                });
    }

    @Test
    void invalidDeepSeekReasoningFailsAtStartup() {
        runner.withPropertyValues("badfisher.ai.enabled=true", "badfisher.ai.default-provider=deepseek",
                "badfisher.ai.default-model=deepseek-flash", "badfisher.ai.providers.openai.enabled=false",
                "badfisher.ai.providers.deepseek.api-type=DEEPSEEK_RESPONSES",
                "badfisher.ai.providers.deepseek.base-url=https://api.deepseek.com",
                "badfisher.ai.providers.deepseek.api-key=synthetic-key",
                "badfisher.ai.providers.deepseek.models.deepseek-flash.reasoning-effort=invalid")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void sameModelNameAcrossProvidersKeepsCredentialsAndRequestParametersIsolated() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Map<String, JsonNode> requests = new ConcurrentHashMap<>();
        Map<String, String> keys = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String provider : new String[] {"openai", "deepseek"}) {
            server.createContext("/" + provider + "/responses", exchange -> {
                requests.put(provider, mapper.readTree(exchange.getRequestBody()));
                keys.put(provider, exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = mapper.writeValueAsBytes(DeepSeekCompatibilityTest.response("""
                        {"judgement":"CONFIRMED","severity":"MEDIUM","category":"CODE",
                         "title":"Test","summary":"Test","analysisBasis":"Test","rootCause":"Test",
                         "impact":"Test","recommendation":{"action":"Test","verification":"Test"},
                         "uncertainty":"Test","confidence":0.7,"humanReviewRequired":true,
                         "ruleSuggestion":null,"suggestedResolutionDays":1}
                        """));
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
        }
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            runner.withPropertyValues("badfisher.ai.enabled=true", "badfisher.ai.default-provider=deepseek",
                    "badfisher.ai.default-model=shared-model",
                    "badfisher.ai.providers.openai.api-key=openai-key",
                    "badfisher.ai.providers.openai.allow-insecure-http=true",
                    "badfisher.ai.providers.openai.base-url=" + base + "/openai",
                    "badfisher.ai.providers.openai.models.shared-model.max-tokens=2000",
                    "badfisher.ai.providers.openai.models.shared-model.reasoning-effort=high",
                    "badfisher.ai.providers.deepseek.api-type=DEEPSEEK_RESPONSES",
                    "badfisher.ai.providers.deepseek.api-key=deepseek-key",
                    "badfisher.ai.providers.deepseek.allow-insecure-http=true",
                    "badfisher.ai.providers.deepseek.base-url=" + base + "/deepseek",
                    "badfisher.ai.providers.deepseek.models.shared-model.max-tokens=4096",
                    "badfisher.ai.providers.deepseek.models.shared-model.reasoning-effort=none")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var registry = context.getBean(AiAnalysisProviderRegistry.class);
                        var input = DeepSeekCompatibilityTest.request(AiAnalysisContract.PROMPT_VERSION);
                        assertThat(registry.get("openai", "shared-model").analyze(input).getProvider()).isEqualTo("openai");
                        assertThat(registry.getDefaultProvider().analyze(input).getProvider()).isEqualTo("deepseek");
                    });
            assertThat(keys).containsEntry("openai", "Bearer openai-key").containsEntry("deepseek", "Bearer deepseek-key");
            assertThat(requests.get("openai").has("reasoning")).isFalse();
            assertThat(requests.get("openai").has("store")).isTrue();
            assertThat(requests.get("openai").path("max_output_tokens").asInt()).isEqualTo(2000);
            assertThat(requests.get("deepseek").has("store")).isFalse();
            assertThat(requests.get("deepseek").path("reasoning").path("effort").asText()).isEqualTo("none");
            assertThat(requests.get("deepseek").path("max_output_tokens").asInt()).isEqualTo(4096);
        } finally {
            server.stop(0);
        }
    }
}
