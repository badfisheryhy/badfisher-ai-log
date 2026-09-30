package io.github.badfisher.ailog.domain.ai;

import lombok.Getter;

/**
 * 已脱敏的 AI 分析证据。
 *
 * <p>本类型是外发安全边界的类型标记：只有经过脱敏网关处理、所有文本字段
 * 均已执行脱敏的证据才允许包装为本类型并进入 Provider 调用链；
 * 原始 {@link AiIssueEvidence} 禁止直接发送到外部 AI。</p>
 *
 * <p>脱敏网关是本类型唯一合法的构造入口，其他代码不得自行包装未经脱敏的证据。</p>
 */
@Getter
public final class SanitizedAiEvidence {

    /** 完成脱敏的证据内容。 */
    private final AiIssueEvidence evidence;

    /**
     * 包装已脱敏证据。
     *
     * @param sanitizedEvidence 已完成脱敏的证据，非空
     */
    public SanitizedAiEvidence(AiIssueEvidence sanitizedEvidence) {
        if (sanitizedEvidence == null) {
            throw new IllegalArgumentException("Sanitized evidence must not be null");
        }
        evidence = sanitizedEvidence;
    }

}
