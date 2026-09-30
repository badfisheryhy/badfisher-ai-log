package io.github.badfisher.ailog.bootstrap.integration.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.badfisher.ailog.bootstrap.config.AiAnalysisProperties.ModelProperties;
import io.github.badfisher.ailog.bootstrap.config.AiAnalysisProperties.ProviderProperties;
import io.github.badfisher.ailog.domain.ai.AiProviderException;
import io.github.badfisher.ailog.domain.ai.AiProviderException.ErrorType;

/** One Responses request per call, using Java 21 HTTP with no hidden application retries. */
public final class OpenAiResponsesClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ProviderProperties provider;
    private final URI endpoint;

    public OpenAiResponsesClient(ObjectMapper mapper, ProviderProperties properties) {
        if (mapper == null || properties == null || !properties.isEnabled()
                || properties.getApiKey() == null || properties.getApiKey().isBlank()
                || properties.getConnectTimeoutSeconds() < 1
                || properties.getReadTimeoutSeconds() < 1) {
            throw new IllegalArgumentException("Enabled AI provider requires a key and positive timeouts");
        }
        URI base = URI.create(properties.getBaseUrl());
        if ((!"https".equals(base.getScheme()) && !"http".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("AI base URL requires HTTP(S) without credentials, query or fragment");
        }
        if ("http".equals(base.getScheme()) && !properties.isAllowInsecureHttp()) {
            throw new IllegalArgumentException("HTTP requires explicit allow-insecure-http=true");
        }
        endpoint = URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/responses");
        provider = properties;
        objectMapper = mapper;
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public JsonNode createResponse(ObjectNode body, String model) {
        ModelProperties options = provider.getModels().get(model);
        if (options == null || !options.isEnabled()
                || options.getMaxTokens() < 1 || options.getMaxTokens() > 32768) {
            throw new IllegalArgumentException("Requested AI model must be enabled with maxTokens in [1,32768]");
        }
        String requestId = UUID.randomUUID().toString();
        ObjectNode payload = body.deepCopy();
        payload.put("model", model);
        payload.put("max_output_tokens", options.getMaxTokens());
        payload.put("stream", false);
        payload.put("store", false);
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
                // Do not retain upstream error bodies: they may echo credentials or source text.
                throw failure(type, "Responses API returned HTTP " + status,
                        requestId, model, "HTTP_" + status);
            }
            JsonNode result = objectMapper.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw failure(ErrorType.INVALID_RESPONSE, "Responses API returned invalid JSON shape",
                        requestId, model, "INVALID_RESPONSE");
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw failure(ErrorType.INVALID_RESPONSE, "Responses API JSON processing failed",
                    requestId, model, "INVALID_JSON");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(ErrorType.NETWORK_FAILURE, "Responses API request interrupted",
                    requestId, model, "INTERRUPTED");
        } catch (IOException exception) {
            throw failure(ErrorType.NETWORK_FAILURE, "Responses API transport failed",
                    requestId, model, "TRANSPORT_FAILURE");
        }
    }

    private static AiProviderException failure(ErrorType type, String message,
            String requestId, String model, String code) {
        return new AiProviderException(type, message, null, requestId, model,
                null, code, null, null, null);
    }
}