package io.github.badfisher.ailog.application.ai;

import lombok.Getter;

/**
 * AI 证据脱敏失败异常。
 *
 * <p>脱敏失败时外发调用必须终止，禁止降级为原文发送；
 * 异常消息只携带 Issue 标识与失败事实，不回显任何原始敏感内容。</p>
 */
@Getter
public final class AiEvidenceSanitizationException extends RuntimeException {

    /** 序列化版本号。 */
    private static final long serialVersionUID = 1L;

    /** 脱敏失败的 Issue 聚合 ID。
     * -- GETTER --
     *  返回脱敏失败的 Issue 聚合 ID。
     *  Issue 聚合 ID
     */
    private final long issueId;

    /**
     * 构造脱敏失败异常。
     *
     * @param failedIssueId 脱敏失败的 Issue 聚合 ID
     * @param cause         底层失败原因
     */
    public AiEvidenceSanitizationException(long failedIssueId, Throwable cause) {
        // 原始 cause 及其 suppressed 异常可能包含敏感证据，不能进入下游日志的异常链。
        super("AI evidence sanitization failed for issue " + failedIssueId
                + (cause == null ? "" : " (" + cause.getClass().getSimpleName() + ")"));
        issueId = failedIssueId;
    }

}
