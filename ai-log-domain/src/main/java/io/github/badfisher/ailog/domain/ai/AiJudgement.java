package io.github.badfisher.ailog.domain.ai;

/**
 * AI 对 Issue 的判定结论。
 *
 * <p>判定只是 AI 的分析结论，不得据此自动修改 Issue、Event 或 expected 状态。</p>
 */
public enum AiJudgement {

    /** 证据足以确认结论。 */
    CONFIRMED,

    /** 疑似原因或证据不足，需进一步确认。 */
    PENDING_CONFIRMATION
}
