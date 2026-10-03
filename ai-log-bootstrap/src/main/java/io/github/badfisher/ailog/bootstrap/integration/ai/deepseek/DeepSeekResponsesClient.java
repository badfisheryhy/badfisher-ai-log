package io.github.badfisher.ailog.bootstrap.integration.ai.deepseek;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.badfisher.ailog.bootstrap.config.AiAnalysisProperties.ModelProperties;
import io.github.badfisher.ailog.bootstrap.config.AiAnalysisProperties.ProviderProperties;
import io.github.badfisher.ailog.bootstrap.integration.ai.ResponsesClient;
import io.github.badfisher.ailog.domain.ai.AiProviderException;
import io.github.badfisher.ailog.domain.ai.AiProviderException.ErrorType;

/**
 * 独立 DeepSeek Responses 传输。无状态多轮历史由调用方提供；此处不执行工具、重试或存储推理内容。
 * OpenAI 客户端不依赖此实现，供应商参数不能进入其他路由的请求。
 */
public final class DeepSeekResponsesClient implements ResponsesClient {

    private static final Set<String> REASONING_EFFORTS = Set.of("none", "low", "high", "max");
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ProviderProperties provider;
    private final URI endpoint;

    public DeepSeekResponsesClient(ObjectMapper mapper, ProviderProperties properties) {
        if (mapper == null || properties == null || !properties.isEnabled()
                || properties.getApiKey() == null || properties.getApiKey().isBlank()
                || properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                || properties.getConnectTimeoutSeconds() < 1
                || properties.getReadTimeoutSeconds() < 1) {
            throw new IllegalArgumentException("Enabled DeepSeek provider requires a key and positive timeouts");
        }
        URI base;
        try {
            base = URI.create(properties.getBaseUrl());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("DeepSeek base URL is malformed");
        }
        if ((!"https".equals(base.getScheme()) && !"http".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || base.getPath().replaceAll("/+$", "").endsWith("/responses")) {
            throw new IllegalArgumentException("DeepSeek base URL requires HTTP(S) without credentials, query, fragment or /responses");
        }
        if ("http".equals(base.getScheme()) && !properties.isAllowInsecureHttp()) {
            throw new IllegalArgumentException("HTTP requires explicit allow-insecure-http=true");
        }
        for (ModelProperties model : properties.getModels().values()) {
            if (model.isEnabled()) {
                validateModel(model);
            }
        }
        endpoint = URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/responses");
        provider = properties;
        objectMapper = mapper;
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public JsonNode createResponse(ObjectNode body, String model) {
        ModelProperties options = provider.getModels().get(model);
        validateModel(options);
        ObjectNode payload = request(body, model, options);
        String requestId = UUID.randomUUID().toString();
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(provider.getReadTimeoutSeconds()))
                    .header("Authorization", "Bearer " + provider.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Client-Request-Id", requestId)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            requestId = response.headers().firstValue("x-request-id").orElse(requestId);
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                ErrorType type = status == 429 ? ErrorType.RATE_LIMITED
                        : status >= 500 ? ErrorType.SERVER_ERROR : ErrorType.REQUEST_ERROR;
                // 不保留上游错误正文，避免凭据、日志或源码回显进入审计。
                throw failure(type, "DeepSeek Responses API returned HTTP " + status,
                        requestId, model, "HTTP_" + status);
            }
            JsonNode result = objectMapper.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw failure(ErrorType.INVALID_RESPONSE, "DeepSeek Responses API returned invalid JSON shape",
                        requestId, model, "INVALID_RESPONSE");
            }
            validateCalls(result, payload, requestId, model);
            return result;
        } catch (JsonProcessingException exception) {
            throw failure(ErrorType.INVALID_RESPONSE, "DeepSeek Responses API JSON processing failed",
                    requestId, model, "INVALID_JSON");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(ErrorType.NETWORK_FAILURE, "DeepSeek Responses API request interrupted",
                    requestId, model, "INTERRUPTED");
        } catch (IOException exception) {
            throw failure(ErrorType.NETWORK_FAILURE, "DeepSeek Responses API transport failed",
                    requestId, model, "TRANSPORT_FAILURE");
        }
    }

    private static void validateModel(ModelProperties model) {
        if (model == null || !model.isEnabled() || model.getMaxTokens() < 1 || model.getMaxTokens() > 32768) {
            throw new IllegalArgumentException("DeepSeek model must be enabled with maxTokens in [1,32768]");
        }
        if (model.getReasoningEffort() != null && !REASONING_EFFORTS.contains(model.getReasoningEffort())) {
            throw new IllegalArgumentException("DeepSeek reasoning-effort must be none, low, high or max");
        }
    }

    private static ObjectNode request(ObjectNode body, String model, ModelProperties options) {
        if (body == null || body.hasNonNull("previous_response_id") || body.hasNonNull("conversation")) {
            throw new AiProviderException(ErrorType.REQUEST_ERROR,
                    "DeepSeek requires full input history; server-side conversation state is unsupported");
        }
        ObjectNode payload = body.deepCopy();
        // 仅在 DeepSeek 边界剔除不支持的 OpenAI 参数；不修改调用方报文或历史。
        payload.remove(Set.of("store", "include", "parallel_tool_calls", "max_tool_calls"));
        payload.put("model", model);
        payload.put("max_output_tokens", options.getMaxTokens());
        payload.put("stream", false);
        if (options.getReasoningEffort() != null) {
            payload.putObject("reasoning").put("effort", options.getReasoningEffort());
        }
        for (JsonNode item : payload.path("input")) {
            if ("reasoning".equals(item.path("type").asText()) && item instanceof ObjectNode reasoning) {
                if (reasoning.hasNonNull("encrypted_content")
                        && (!reasoning.path("content").isArray() || reasoning.path("content").isEmpty())) {
                    throw new AiProviderException(ErrorType.REQUEST_ERROR,
                            "DeepSeek requires plaintext reasoning history; encrypted-only history is unsupported");
                }
                reasoning.remove(Set.of("encrypted_content", "summary"));
            }
        }
        return payload;
    }

    private static void validateCalls(JsonNode response, ObjectNode payload, String requestId, String model) {
        Set<String> callIds = new HashSet<>();
        for (JsonNode item : payload.path("input")) {
            if ("function_call".equals(item.path("type").asText())) {
                callIds.add(item.path("call_id").asText());
            }
        }
        for (JsonNode item : response.path("output")) {
            if ("function_call".equals(item.path("type").asText())) {
                JsonNode id = item.get("call_id");
                if (id == null || !id.isTextual() || id.asText().isBlank() || !callIds.add(id.asText())) {
                    JsonNode usage = response.path("usage");
                    throw new AiProviderException(ErrorType.INVALID_RESPONSE,
                            "DeepSeek returned empty or duplicate tool call IDs", null,
                            response.path("id").asText(requestId), response.path("model").asText(model),
                            null, "INVALID_TOOL_CALL", integer(usage, "input_tokens"),
                            integer(usage, "output_tokens"), integer(usage, "total_tokens"));
                }
            }
        }
    }

    private static Integer integer(JsonNode usage, String field) {
        JsonNode value = usage.get(field);
        return value != null && value.canConvertToInt() ? value.intValue() : null;
    }

    private static AiProviderException failure(ErrorType type, String message,
            String requestId, String model, String code) {
        return new AiProviderException(type, message, null, requestId, model,
                null, code, null, null, null);
    }
}
