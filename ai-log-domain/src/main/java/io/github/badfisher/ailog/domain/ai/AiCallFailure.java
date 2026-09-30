package io.github.badfisher.ailog.domain.ai;

import lombok.Getter;

/** 一次失败 Attempt 的安全错误事实和可获得的供应商响应元数据。 */
@Getter
public final class AiCallFailure {
    private final String errorType;
    private final String errorCode;
    private final String errorMessage;
    private final boolean retryable;
    private final String providerRequestId;
    private final String actualModel;
    private final String rawResponse;
    private final String finishReason;
    private final Integer inputTokens;
    private final Integer outputTokens;
    private final Integer totalTokens;

    public AiCallFailure(String type, String code, String message, boolean canRetry,
            String requestId, String model, String response, String responseFinishReason,
            Integer tokensInput, Integer tokensOutput, Integer tokensTotal) {
        errorType = type;
        errorCode = code;
        errorMessage = message;
        retryable = canRetry;
        providerRequestId = requestId;
        actualModel = model;
        rawResponse = response;
        finishReason = responseFinishReason;
        inputTokens = tokensInput;
        outputTokens = tokensOutput;
        totalTokens = tokensTotal;
    }

}
