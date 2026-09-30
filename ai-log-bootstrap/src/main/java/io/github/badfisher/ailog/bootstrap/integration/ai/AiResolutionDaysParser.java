package io.github.badfisher.ailog.bootstrap.integration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.badfisher.ailog.domain.ai.AiProviderException;
import io.github.badfisher.ailog.domain.ai.AiProviderException.ErrorType;

/** 统一解析 AI 响应中的建议解决天数，部分天数向上取整。 */
final class AiResolutionDaysParser {

    private AiResolutionDaysParser() {
    }

    static int parse(JsonNode node, String errorMessage) {
        if (node == null || !node.isNumber()) {
            throw new AiProviderException(ErrorType.INVALID_RESPONSE, errorMessage);
        }
        double days = node.asDouble();
        if (!Double.isFinite(days) || days <= 0D || days > Integer.MAX_VALUE) {
            throw new AiProviderException(ErrorType.INVALID_RESPONSE, errorMessage);
        }
        return (int) Math.ceil(days);
    }
}
