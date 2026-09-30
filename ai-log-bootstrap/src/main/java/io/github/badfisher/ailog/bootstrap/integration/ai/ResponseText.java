package io.github.badfisher.ailog.bootstrap.integration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.badfisher.ailog.domain.ai.AiProviderException;
import io.github.badfisher.ailog.domain.ai.AiProviderException.ErrorType;
import lombok.Getter;

/** Completed Responses output text and usage, independent of any external starter. */
@Getter
final class ResponseText {

    private final String content;
    private final String requestId;
    private final String model;
    private final String finishReason;
    private final Integer inputTokens;
    private final Integer outputTokens;
    private final Integer totalTokens;

    ResponseText(JsonNode response) {
        requestId = response.path("id").asText(null);
        model = response.path("model").asText(null);
        finishReason = response.path("status").asText(null);
        JsonNode usage = response.path("usage");
        inputTokens = integer(usage, "input_tokens");
        outputTokens = integer(usage, "output_tokens");
        totalTokens = integer(usage, "total_tokens");
        if (!"completed".equals(finishReason)) {
            throw invalid("Responses API did not complete");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode item : response.path("output")) {
            for (JsonNode part : item.path("content")) {
                if ("refusal".equals(part.path("type").asText())) {
                    throw invalid("Responses API refused the analysis");
                }
                if ("output_text".equals(part.path("type").asText())) {
                    text.append(part.path("text").asText());
                }
            }
        }
        content = text.toString();
    }

    private AiProviderException invalid(String message) {
        return new AiProviderException(ErrorType.INVALID_RESPONSE, message, null,
                requestId, model, null, finishReason, inputTokens, outputTokens, totalTokens);
    }

    private static Integer integer(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.canConvertToInt() ? value.intValue() : null;
    }
}