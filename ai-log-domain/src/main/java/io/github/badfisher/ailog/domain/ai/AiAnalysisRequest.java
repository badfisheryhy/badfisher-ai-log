package io.github.badfisher.ailog.domain.ai;

import lombok.Getter;

/**
 * 单个 Issue 的 AI 分析请求。
 *
 * <p>请求只携带已脱敏证据与 Prompt 版本；Provider 适配器负责构建具体协议报文，
 * 不得在此暴露厂商专属结构。</p>
 */
@Getter
public final class AiAnalysisRequest {

    /** 已脱敏的待分析证据。 */
    private final SanitizedAiEvidence evidence;

    /** 本次分析使用的 Prompt 版本。 */
    private final String promptVersion;

    /**
     * 构造分析请求。
     *
     * @param sanitizedEvidence 已脱敏证据，非空
     * @param prompt            Prompt 版本，非空
     */
    public AiAnalysisRequest(SanitizedAiEvidence sanitizedEvidence, String prompt) {
        if (sanitizedEvidence == null) {
            throw new IllegalArgumentException("Sanitized evidence must not be null");
        }
        if (prompt == null || prompt.isEmpty()) {
            throw new IllegalArgumentException("Prompt version must not be empty");
        }
        evidence = sanitizedEvidence;
        promptVersion = prompt;
    }

}
