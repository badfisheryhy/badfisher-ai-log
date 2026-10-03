package io.github.badfisher.ailog.bootstrap.integration.ai;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.badfisher.ailog.bootstrap.config.AiAnalysisProperties;
import io.github.badfisher.ailog.domain.ai.AiProviderException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiResponsesClientTest {

    @Test
    void rejectsHttpByDefaultAndAcceptsHttpsWithoutOptIn() {
        AiAnalysisProperties.ProviderProperties properties = new AiAnalysisProperties.ProviderProperties();
        properties.setApiKey("synthetic-test-key");
        properties.setBaseUrl("http://127.0.0.1:1/v1");
        assertThatThrownBy(() -> new OpenAiResponsesClient(new ObjectMapper(), properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allow-insecure-http");
        properties.setBaseUrl("https://example.invalid/v1");
        assertThat(new OpenAiResponsesClient(new ObjectMapper(), properties)).isNotNull();
    }

    @Test
    void sendsResponsesContractAndUsesConfiguredKeyModelAndBaseUrl() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(mapper.readTree(exchange.getRequestBody()));
            byte[] body = "{\"id\":\"resp-test\",\"status\":\"completed\",\"output\":[]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AiAnalysisProperties.ProviderProperties configured = properties(server);
            configured.getModels().get("test-model").setReasoningEffort("high");
            OpenAiResponsesClient client = new OpenAiResponsesClient(mapper, configured);
            JsonNode result = client.createResponse(mapper.createObjectNode().put("input", "synthetic evidence"), "test-model");
            assertThat(authorization.get()).isEqualTo("Bearer synthetic-test-key");
            assertThat(payload.get().path("model").asText()).isEqualTo("test-model");
            assertThat(payload.get().has("reasoning")).isFalse();
            assertThat(payload.get().path("store").asBoolean()).isFalse();
            assertThat(payload.get().path("stream").asBoolean()).isFalse();
            assertThat(payload.get().path("max_output_tokens").asInt()).isEqualTo(2000);
            assertThat(result.path("id").asText()).isEqualTo("resp-test");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsRateLimitWithoutHiddenRetryOrUpstreamSecretLeak() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"error\":\"secret-from-upstream\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            OpenAiResponsesClient client = new OpenAiResponsesClient(mapper, properties(server));
            assertThatThrownBy(() -> client.createResponse(mapper.createObjectNode().put("input", "test"), "test-model"))
                    .isInstanceOfSatisfying(AiProviderException.class, exception -> {
                        assertThat(exception.getErrorType()).isEqualTo(AiProviderException.ErrorType.RATE_LIMITED);
                        assertThat(exception.getRawResponse()).isNull();
                        assertThat(exception.getMessage()).doesNotContain("secret-from-upstream");
                    });
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void incompleteOrRefusedResponsesCannotBecomeSuccessfulAnalysis() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode incomplete = mapper.readTree("{\"status\":\"incomplete\",\"usage\":{\"input_tokens\":12}}");
        assertThatThrownBy(() -> new ResponseText(incomplete))
                .isInstanceOfSatisfying(AiProviderException.class,
                        exception -> assertThat(exception.getInputTokens()).isEqualTo(12));
        JsonNode refused = mapper.readTree("{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"refusal\"}]}]}");
        assertThatThrownBy(() -> new ResponseText(refused)).isInstanceOf(AiProviderException.class);
    }

    private static AiAnalysisProperties.ProviderProperties properties(HttpServer server) {
        AiAnalysisProperties.ProviderProperties properties = new AiAnalysisProperties.ProviderProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setApiKey("synthetic-test-key");
        properties.setAllowInsecureHttp(true);
        properties.setModels(Map.of("test-model", new AiAnalysisProperties.ModelProperties()));
        return properties;
    }
}
