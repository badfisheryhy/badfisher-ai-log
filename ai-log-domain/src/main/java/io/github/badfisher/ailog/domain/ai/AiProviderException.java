package io.github.badfisher.ailog.domain.ai;

import lombok.Getter;

/**
 * AI Provider 调用失败异常。
 *
 * <p>异常消息只包含错误分类与状态等事实，不得携带 API Key、完整请求体或未脱敏日志内容；
 * 调用方根据 {@link ErrorType} 决定是否允许后续重试或标记任务失败。</p>
 */
@Getter
public final class AiProviderException extends RuntimeException {

    /** 序列化版本号。 */
    private static final long serialVersionUID = 1L;

    /** Provider 失败分类。
     * -- GETTER --
     *  返回失败分类。
     *
     * @return 失败分类
     */
    private final ErrorType errorType;
    private final String providerRequestId;
    private final String actualModel;
    private final String rawResponse;
    private final String finishReason;
    private final Integer inputTokens;
    private final Integer outputTokens;
    private final Integer totalTokens;

    /**
     * 构造 Provider 失败异常。
     *
     * @param type    失败分类
     * @param message 失败事实描述
     */
    public AiProviderException(ErrorType type, String message) {
        this(type, message, null);
    }

    /**
     * 构造 Provider 失败异常。
     *
     * @param type    失败分类
     * @param message 失败事实描述
     * @param cause   底层原因
     */
    public AiProviderException(ErrorType type, String message, Throwable cause) {
        this(type, message, cause, null, null, null, null, null, null, null);
    }

    /**
     * 构造带供应商响应元数据的失败；用于非法结构化响应的完整 Attempt 审计。
     */
    public AiProviderException(ErrorType type, String message, Throwable cause,
            String requestId, String model, String response, String responseFinishReason,
            Integer tokensInput, Integer tokensOutput, Integer tokensTotal) {
        super(message, cause);
        errorType = type;
        providerRequestId = requestId;
        actualModel = model;
        rawResponse = response;
        finishReason = responseFinishReason;
        inputTokens = tokensInput;
        outputTokens = tokensOutput;
        totalTokens = tokensTotal;
    }

    /** Provider 失败分类：决定调用方的重试与失败语义。 */
    public enum ErrorType {

        /** 网络连接或读写失败，可重试。 */
        NETWORK_FAILURE,

        /** 服务端限流（HTTP 429），退避后可重试。 */
        RATE_LIMITED,

        /** 服务端错误（HTTP 5xx），可重试。 */
        SERVER_ERROR,

        /** 请求错误（HTTP 4xx），配置或请求非法，不盲目重试。 */
        REQUEST_ERROR,

        /** 响应为空、非法 JSON 或不符合输出契约，不作为成功结果落库。 */
        INVALID_RESPONSE
    }
}
