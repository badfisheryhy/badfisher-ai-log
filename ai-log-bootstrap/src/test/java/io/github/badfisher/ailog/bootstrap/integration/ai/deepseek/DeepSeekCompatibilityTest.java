package io.github.badfisher.ailog.bootstrap.integration.ai.deepseek;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.badfisher.ailog.application.ai.AiEvidenceSanitizer;
import io.github.badfisher.ailog.bootstrap.config.AiAnalysisProperties.ModelProperties;
import io.github.badfisher.ailog.bootstrap.config.AiAnalysisProperties.ProviderProperties;
import io.github.badfisher.ailog.bootstrap.integration.ai.FunctionCallingAnalysisProperties;
import io.github.badfisher.ailog.bootstrap.integration.ai.OpenAiAnalysisProvider;
import io.github.badfisher.ailog.bootstrap.integration.ai.OpenAiFunctionCallingAnalysisProvider;
import io.github.badfisher.ailog.bootstrap.integration.git.GitSyncProperties;
import io.github.badfisher.ailog.bootstrap.integration.git.GitWorkspacePathResolver;
import io.github.badfisher.ailog.domain.ai.AiAnalysisContract;
import io.github.badfisher.ailog.domain.ai.AiAnalysisRequest;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence;
import io.github.badfisher.ailog.domain.ai.AiProviderException;
import io.github.badfisher.ailog.domain.ai.AiProviderException.ErrorType;
import io.github.badfisher.ailog.parser.security.SensitiveLogSanitizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekCompatibilityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String MODEL = "deepseek-test-model";
    private static final String FINAL_RESULT = """
            {"judgement":"CONFIRMED","severity":"MEDIUM","category":"CODE",
             "title":"Synthetic test","summary":"Synthetic analysis result",
             "analysisBasis":"Supplied exception","rootCause":"Synthetic state failure",
             "impact":"One operation","recommendation":{"action":"Check state","verification":"Replay test"},
             "uncertainty":"No production evidence","confidence":0.7,
             "humanReviewRequired":true,"ruleSuggestion":null,"suggestedResolutionDays":2}
            """;
    private static final String TOOL_RESULT = """
            {"title":"Synthetic source analysis","actualProblem":"YES","needChange":"YES",
             "changeScope":"Service.run","category":"CODE","severity":"MEDIUM",
             "conclusion":"Guard missing","impact":"One operation",
             "directCause":"Synthetic throw","rootCause":"Missing guard",
             "changes":["Add guard"],"doNotChange":[],"evidence":["Service.run throws"],
             "missing":[],"verify":["Replay"],"suggestedResolutionDays":1,
             "confidence":{"verdict":0.9,"directCause":0.9,"rootCause":0.9}}
            """;

    @TempDir
    Path directory;

    @Test
    void sendsIsolatedDeepSeekContractWithoutMutatingInput() throws Exception {
        try (Stub stub = new Stub(n -> response(FINAL_RESULT))) {
            ProviderProperties properties = stub.properties();
            properties.getModels().get(MODEL).setReasoningEffort("none");
            properties.getModels().get(MODEL).setMaxTokens(4096);
            ObjectNode body = MAPPER.createObjectNode().put("input", "synthetic evidence");
            body.put("store", false).put("parallel_tool_calls", false).put("max_tool_calls", 1);
            body.putArray("include").add("reasoning.encrypted_content");
            ObjectNode original = body.deepCopy();
            new DeepSeekResponsesClient(MAPPER, properties).createResponse(body, MODEL);
            JsonNode sent = stub.requests.getFirst();
            assertThat(stub.authorizations).containsExactly("Bearer deepseek-synthetic-key");
            assertThat(sent.path("model").asText()).isEqualTo(MODEL);
            assertThat(sent.path("max_output_tokens").asInt()).isEqualTo(4096);
            assertThat(sent.path("reasoning").path("effort").asText()).isEqualTo("none");
            assertThat(sent.path("stream").asBoolean()).isFalse();
            for (String unsupported : List.of("store", "include", "parallel_tool_calls", "max_tool_calls")) {
                assertThat(sent.has(unsupported)).as(unsupported).isFalse();
            }
            assertThat(body).isEqualTo(original);
        }
    }

    @Test
    void ordinaryAnalysisUsesOnlyFinalTextAndReturnsProviderMetadata() throws Exception {
        try (Stub stub = new Stub(n -> response(FINAL_RESULT))) {
            var provider = new OpenAiAnalysisProvider(new DeepSeekResponsesClient(MAPPER, stub.properties()),
                    MAPPER, "deepseek", MODEL);
            var result = provider.analyze(request(AiAnalysisContract.PROMPT_VERSION));
            assertThat(result.getProvider()).isEqualTo("deepseek");
            assertThat(result.getModel()).isEqualTo(MODEL);
            assertThat(result.getProviderRequestId()).isEqualTo("resp-deepseek");
            assertThat(result.getInputTokens()).isEqualTo(10);
            assertThat(result.getOutputTokens()).isEqualTo(5);
            assertThat(result.getTotalTokens()).isEqualTo(15);
            assertThat(result.getRawResponse()).doesNotContain("private-reasoning");
            assertThat(stub.requests.getFirst().path("instructions").asText())
                    .isEqualTo(AiAnalysisContract.SYSTEM_INSTRUCTION);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{}"})
    void invalidFinalContentCannotBecomeSuccessfulAnalysis(String content) throws Exception {
        try (Stub stub = new Stub(n -> response(content))) {
            var provider = new OpenAiAnalysisProvider(new DeepSeekResponsesClient(MAPPER, stub.properties()),
                    MAPPER, "deepseek", MODEL);
            assertThatThrownBy(() -> provider.analyze(request(AiAnalysisContract.PROMPT_VERSION)))
                    .isInstanceOf(AiProviderException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"incomplete", "failed"})
    void nonCompletedResponsesRetainKnownUsageAndFail(String status) throws Exception {
        try (Stub stub = new Stub(n -> response(FINAL_RESULT).put("status", status))) {
            var provider = new OpenAiAnalysisProvider(new DeepSeekResponsesClient(MAPPER, stub.properties()),
                    MAPPER, "deepseek", MODEL);
            assertThatThrownBy(() -> provider.analyze(request(AiAnalysisContract.PROMPT_VERSION)))
                    .isInstanceOfSatisfying(AiProviderException.class, error -> {
                        assertThat(error.getInputTokens()).isEqualTo(10);
                        assertThat(error.getFinishReason()).isEqualTo(status);
                    });
        }
    }

    @ParameterizedTest
    @CsvSource({"302,REQUEST_ERROR", "400,REQUEST_ERROR", "401,REQUEST_ERROR", "402,REQUEST_ERROR", "429,RATE_LIMITED",
            "500,SERVER_ERROR", "503,SERVER_ERROR"})
    void mapsErrorsWithoutHiddenRetryOrSecretLeak(int status, ErrorType type) throws Exception {
        try (Stub stub = new Stub(n -> MAPPER.createObjectNode().put("error", "secret-from-upstream"))) {
            stub.status = status;
            assertThatThrownBy(() -> new DeepSeekResponsesClient(MAPPER, stub.properties())
                    .createResponse(MAPPER.createObjectNode().put("input", "test"), MODEL))
                    .isInstanceOfSatisfying(AiProviderException.class, error -> {
                        assertThat(error.getErrorType()).isEqualTo(type);
                        assertThat(error.getRawResponse()).isNull();
                        assertThat(error.getMessage()).doesNotContain("secret-from-upstream", "deepseek-synthetic-key");
                        assertThat(error.getProviderRequestId()).isEqualTo("http-deepseek-test");
                    });
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test
    void supportsMultipleToolCallsAndFullPlaintextReasoningHistoryWithinLocalBudget() throws Exception {
        Files.createDirectories(directory.resolve("test/demo/service"));
        Files.writeString(directory.resolve("test/demo/service/Service.java"),
                "class Service { void run() { throw new IllegalStateException(); } }");
        try (Stub stub = new Stub(n -> n == 0 ? toolCalls() : response(TOOL_RESULT))) {
            ProviderProperties properties = stub.properties();
            properties.getModels().get(MODEL).setReasoningEffort("high");
            FunctionCallingAnalysisProperties tools = new FunctionCallingAnalysisProperties();
            tools.setEnabled(true);
            tools.setMaxToolCalls(1);
            GitSyncProperties git = new GitSyncProperties();
            git.setWorkspace(directory.toString());
            var provider = new OpenAiFunctionCallingAnalysisProvider(new DeepSeekResponsesClient(MAPPER, properties),
                    MAPPER, tools, new GitWorkspacePathResolver(git), "deepseek", MODEL);
            var result = provider.analyze(request(AiAnalysisContract.FUNCTION_CALLING_PROMPT_VERSION));
            assertThat(stub.requests).hasSize(2);
            JsonNode second = stub.requests.get(1);
            assertThat(second.path("tool_choice").asText()).isEqualTo("none");
            assertThat(second.path("reasoning").path("effort").asText()).isEqualTo("high");
            List<JsonNode> history = new ArrayList<>();
            second.path("input").forEach(history::add);
            assertThat(history).anySatisfy(item -> {
                assertThat(item.path("type").asText()).isEqualTo("reasoning");
                assertThat(item.path("content").get(0).path("text").asText()).isEqualTo("private-reasoning");
            });
            List<JsonNode> outputs = history.stream()
                    .filter(item -> "function_call_output".equals(item.path("type").asText())).toList();
            assertThat(outputs).hasSize(2);
            assertThat(outputs.get(0).path("call_id").asText()).isEqualTo("call-1");
            assertThat(outputs.get(0).path("output").asText()).contains("Service.java", "IllegalStateException");
            assertThat(outputs.get(1).path("call_id").asText()).isEqualTo("call-2");
            assertThat(outputs.get(1).path("output").asText()).contains("SOURCE_TOOL_BUDGET_EXHAUSTED");
            assertThat(result.getInputTokens()).isEqualTo(20);
            assertThat(result.getTotalTokens()).isEqualTo(30);
            assertThat(result.getRawResponse()).doesNotContain("private-reasoning");
            assertThat(stub.requests.getFirst().path("text").path("format").path("type").asText())
                    .isEqualTo("json_schema");
        }
    }

    @Test
    void toolRequestsDuringFinalizationCannotBecomeSuccessfulAnalysis() throws Exception {
        Files.createDirectories(directory.resolve("test/demo/service"));
        try (Stub stub = new Stub(n -> {
            ObjectNode response = toolCalls();
            for (int index = 1; index <= 2; index++) {
                ((ObjectNode) response.path("output").get(index)).put("call_id", "round-" + n + "-" + index);
            }
            return response;
        })) {
            FunctionCallingAnalysisProperties tools = new FunctionCallingAnalysisProperties();
            tools.setMaxToolCalls(1);
            GitSyncProperties git = new GitSyncProperties();
            git.setWorkspace(directory.toString());
            var provider = new OpenAiFunctionCallingAnalysisProvider(new DeepSeekResponsesClient(MAPPER, stub.properties()),
                    MAPPER, tools, new GitWorkspacePathResolver(git), "deepseek", MODEL);
            assertThatThrownBy(() -> provider.analyze(request(AiAnalysisContract.FUNCTION_CALLING_PROMPT_VERSION)))
                    .isInstanceOf(AiProviderException.class).hasMessageContaining("finalization");
            assertThat(stub.requests).hasSize(2);
        }
    }

    @Test
    void rejectsDuplicateToolCallIdsBeforeAnyToolExecution() throws Exception {
        try (Stub stub = new Stub(n -> {
            ObjectNode response = toolCalls();
            ((ObjectNode) response.path("output").get(2)).put("call_id", "call-1");
            return response;
        })) {
            assertThatThrownBy(() -> new DeepSeekResponsesClient(MAPPER, stub.properties())
                    .createResponse(MAPPER.createObjectNode().put("input", "test"), MODEL))
                    .isInstanceOf(AiProviderException.class).hasMessageContaining("duplicate");
        }
    }

    @Test
    void rejectsToolCallIdReusedFromAnEarlierRoundAndRetainsUsage() throws Exception {
        try (Stub stub = new Stub(n -> toolCalls())) {
            ObjectNode body = MAPPER.createObjectNode();
            body.putArray("input").addObject().put("type", "function_call").put("call_id", "call-1")
                    .put("name", "search_code").put("arguments", "{}");
            body.withArray("input").addObject().put("type", "function_call_output").put("call_id", "call-1")
                    .put("output", "{}");
            assertThatThrownBy(() -> new DeepSeekResponsesClient(MAPPER, stub.properties()).createResponse(body, MODEL))
                    .isInstanceOfSatisfying(AiProviderException.class, error -> {
                        assertThat(error.getFinishReason()).isEqualTo("INVALID_TOOL_CALL");
                        assertThat(error.getInputTokens()).isEqualTo(10);
                        assertThat(error.getOutputTokens()).isEqualTo(5);
                        assertThat(error.getRawResponse()).isNull();
                    });
        }
    }

    @Test
    void rejectsUnsafeConnectionInvalidModelAndServerSideState() throws Exception {
        ProviderProperties properties = new ProviderProperties();
        properties.setApiKey("test");
        properties.setBaseUrl("http://127.0.0.1:1");
        assertThatThrownBy(() -> new DeepSeekResponsesClient(MAPPER, properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allow-insecure-http");
        properties.setBaseUrl("https://example.invalid");
        ModelProperties model = new ModelProperties();
        model.setReasoningEffort("invalid");
        properties.setModels(Map.of(MODEL, model));
        assertThatThrownBy(() -> new DeepSeekResponsesClient(MAPPER, properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reasoning-effort");
        model.setReasoningEffort(null);
        assertThat(new DeepSeekResponsesClient(MAPPER, properties)).isNotNull();
        try (Stub stub = new Stub(n -> response(FINAL_RESULT))) {
            assertThatThrownBy(() -> new DeepSeekResponsesClient(MAPPER, stub.properties()).createResponse(
                    MAPPER.createObjectNode().put("previous_response_id", "resp-old"), MODEL))
                    .isInstanceOf(AiProviderException.class).hasMessageContaining("full input history");
            assertThat(stub.requests).isEmpty();
        }
    }

    static AiAnalysisRequest request(String prompt) {
        AiIssueEvidence evidence = new AiIssueEvidence(1L, "test", "demo", "service", "fp", "fp-v1", "CODE",
                "MANUAL", "IllegalStateException", "IllegalStateException", "Service", "run", "failed", null,
                1L, LocalDateTime.of(2026, 10, 1, 10, 0), LocalDateTime.of(2026, 10, 1, 10, 0),
                "synthetic failure", "at Service.run(Service.java:1)", List.of());
        return new AiAnalysisRequest(new AiEvidenceSanitizer(new SensitiveLogSanitizer()).sanitize(evidence), prompt);
    }

    static ObjectNode response(String result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("id", "resp-deepseek").put("model", MODEL).put("status", "completed");
        response.putArray("output").addObject().put("type", "reasoning").putArray("content")
                .addObject().put("type", "reasoning_text").put("text", "private-reasoning");
        response.withArray("output").addObject().put("type", "message").putArray("content")
                .addObject().put("type", "output_text").put("text", result);
        response.putObject("usage").put("input_tokens", 10).put("output_tokens", 5).put("total_tokens", 15);
        return response;
    }

    private static ObjectNode toolCalls() {
        ObjectNode response = response("");
        response.withArray("output").remove(1);
        for (int number = 1; number <= 2; number++) {
            response.withArray("output").addObject().put("type", "function_call")
                    .put("call_id", "call-" + number).put("name", "search_code")
                    .put("arguments", "{\"query\":\"Service\",\"fileName\":null,\"maxResults\":1}");
        }
        return response;
    }

    @Test
    void missingUsageStaysUnknownAndUnknownResultFieldsAreRejected() throws Exception {
        try (Stub stub = new Stub(n -> {
            ObjectNode result = response(FINAL_RESULT);
            result.remove("usage");
            return result;
        })) {
            var provider = new OpenAiAnalysisProvider(new DeepSeekResponsesClient(MAPPER, stub.properties()),
                    MAPPER, "deepseek", MODEL);
            var result = provider.analyze(request(AiAnalysisContract.PROMPT_VERSION));
            assertThat(result.getInputTokens()).isNull();
            assertThat(result.getOutputTokens()).isNull();
            assertThat(result.getTotalTokens()).isNull();
        }
        ObjectNode invalid = (ObjectNode) MAPPER.readTree(FINAL_RESULT);
        invalid.put("unexpected", "not allowed");
        try (Stub stub = new Stub(n -> response(invalid.toString()))) {
            var provider = new OpenAiAnalysisProvider(new DeepSeekResponsesClient(MAPPER, stub.properties()),
                    MAPPER, "deepseek", MODEL);
            assertThatThrownBy(() -> provider.analyze(request(AiAnalysisContract.PROMPT_VERSION)))
                    .isInstanceOf(AiProviderException.class).hasMessageContaining("unknown field");
        }
    }

    @Test
    void encryptedOnlyReasoningHistoryIsRejectedBeforeSending() throws Exception {
        try (Stub stub = new Stub(n -> response(FINAL_RESULT))) {
            ObjectNode input = MAPPER.createObjectNode();
            input.putArray("input").addObject().put("type", "reasoning").put("encrypted_content", "ciphertext");
            assertThatThrownBy(() -> new DeepSeekResponsesClient(MAPPER, stub.properties())
                    .createResponse(input, MODEL)).isInstanceOf(AiProviderException.class)
                    .hasMessageContaining("plaintext reasoning");
            assertThat(stub.requests).isEmpty();
            assertThat(input.path("input").get(0).path("encrypted_content").asText()).isEqualTo("ciphertext");
        }
    }

    @Test
    void sourceToolCannotReadOutsideItsEvidenceRepository() throws Exception {
        Files.createDirectories(directory.resolve("test/demo/service"));
        Files.writeString(directory.resolve("secret.java"), "never-send-this-private-source");
        try (Stub stub = new Stub(n -> {
            if (n != 0) {
                return response(TOOL_RESULT);
            }
            ObjectNode response = toolCalls();
            response.withArray("output").remove(2);
            ((ObjectNode) response.path("output").get(1)).put("name", "read_source")
                    .put("arguments", "{\"path\":\"../../../secret.java\",\"startLine\":1,\"endLine\":1}");
            return response;
        })) {
            FunctionCallingAnalysisProperties tools = new FunctionCallingAnalysisProperties();
            GitSyncProperties git = new GitSyncProperties();
            git.setWorkspace(directory.toString());
            var provider = new OpenAiFunctionCallingAnalysisProvider(new DeepSeekResponsesClient(MAPPER, stub.properties()),
                    MAPPER, tools, new GitWorkspacePathResolver(git), "deepseek", MODEL);
            provider.analyze(request(AiAnalysisContract.FUNCTION_CALLING_PROMPT_VERSION));
            assertThat(stub.requests).hasSize(2);
            assertThat(stub.requests.get(1).toString()).contains("error").doesNotContain("never-send-this-private-source");
        }
    }

    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        private final List<JsonNode> requests = new ArrayList<>();
        private final List<String> authorizations = new ArrayList<>();
        private int status = 200;

        private Stub(IntFunction<ObjectNode> responder) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/responses", exchange -> {
                JsonNode request = MAPPER.readTree(exchange.getRequestBody());
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                int number = requests.size();
                requests.add(request);
                byte[] response = MAPPER.writeValueAsBytes(responder.apply(number));
                exchange.getResponseHeaders().add("x-request-id", "http-deepseek-test");
                exchange.sendResponseHeaders(status, response.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
            });
            server.start();
        }

        private ProviderProperties properties() {
            ProviderProperties properties = new ProviderProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("deepseek-synthetic-key");
            properties.setAllowInsecureHttp(true);
            properties.setModels(Map.of(MODEL, new ModelProperties()));
            return properties;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
