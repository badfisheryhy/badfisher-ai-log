package io.github.badfisher.ailog.application.ai;

import java.util.ArrayList;
import java.util.List;
import io.github.badfisher.ailog.domain.ai.InfoContext;

import io.github.badfisher.ailog.domain.ai.AiIssueEvidence;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence.EvidenceSample;
import io.github.badfisher.ailog.domain.ai.SanitizedAiEvidence;
import io.github.badfisher.ailog.parser.security.SensitiveLogSanitizer;

/**
 * AI 证据脱敏网关：Evidence 外发前的唯一合法脱敏入口。
 *
 * <p>固定流程：{@code AiIssueEvidence -> SensitiveLogSanitizer -> SanitizedAiEvidence -> AI Provider}。
 * 网关对证据的全部文本字段（含样本消息与调用栈）执行脱敏并生成新副本，
 * 原始 Evidence 保持不变；结构化字段（ID、计数、时间）原样保留。</p>
 *
 * <p>脱敏失败时抛出 {@link AiEvidenceSanitizationException} 终止外发，
 * 异常不回显敏感原文，调用方应把对应 Issue 标记为 {@code FAILED/SANITIZE_FAILED}。</p>
 */
public final class AiEvidenceSanitizer {

    /** 可控的单字段脱敏函数，便于验证失败闭合分支。 */
    interface TextSanitizer {

        /**
         * 对单个文本字段执行外发脱敏。
         *
         * @param value 原始字段
         * @return 脱敏字段
         */
        String sanitize(String value);
    }

    private final TextSanitizer sanitizer;

    /**
     * 构造脱敏网关。
     *
     * @param logSanitizer 底层外发脱敏器，非空
     */
    public AiEvidenceSanitizer(SensitiveLogSanitizer logSanitizer) {
        if (logSanitizer == null) {
            throw new IllegalArgumentException("SensitiveLogSanitizer不能为空");
        }
        sanitizer = logSanitizer::sanitizeForExternal;
    }

    /**
     * 使用可控脱敏函数构造网关，仅供同包测试和内部装配验证失败闭合分支。
     *
     * @param textSanitizer 单字段脱敏函数，非空
     */
    AiEvidenceSanitizer(TextSanitizer textSanitizer) {
        if (textSanitizer == null) {
            throw new IllegalArgumentException("TextSanitizer不能为空");
        }
        sanitizer = textSanitizer;
    }

    /**
     * 对证据生成脱敏副本。
     *
     * @param evidence 原始证据，非空
     * @return 已脱敏证据，可直接进入 Provider 调用链
     * @throws AiEvidenceSanitizationException 脱敏过程失败，外发必须终止
     */
    public SanitizedAiEvidence sanitize(AiIssueEvidence evidence) {
        if (evidence == null) {
            throw new IllegalArgumentException("证据不能为空");
        }
        try {
            List<EvidenceSample> sanitizedSamples = new ArrayList<EvidenceSample>(
                    evidence.getSamples().size());
            for (EvidenceSample sample : evidence.getSamples()) {
                sanitizedSamples.add(new EvidenceSample(sample.getEventId(), sample.getLogTime(),
                        sanitize(sample.getMatchType()), sample.isTruncated(),
                        sanitize(sample.getExceptionClass()),
                        sanitize(sample.getExceptionMessage()),
                        sanitize(sample.getRootCauseException()),
                        sanitize(sample.getRootCauseMessage()),
                        sanitize(sample.getNormalizedMessage()),
                        sanitize(sample.getSimplifiedStack()),
                        sanitize(sample.getSampleContent()),
                        sample.isSampleContentTruncated()));
            }
            AiIssueEvidence sanitized = new AiIssueEvidence(evidence.getIssueId(),
                    sanitize(evidence.getEnvironment()), sanitize(evidence.getSystemCode()),
                    sanitize(evidence.getModuleCode()), sanitize(evidence.getStableFingerprint()),
                    sanitize(evidence.getFingerprintVersion()),
                    sanitize(evidence.getRootCauseCategory()), sanitize(evidence.getTriggerChannel()),
                    sanitize(evidence.getExceptionClass()),
                    sanitize(evidence.getRootCauseException()),
                    sanitize(evidence.getBusinessClass()), sanitize(evidence.getBusinessMethod()),
                    sanitize(evidence.getMessageTemplate()), evidence.getMatchedRuleId(),
                    evidence.getOccurrenceCount(), evidence.getFirstOccurredAt(),
                    evidence.getLastOccurredAt(), sanitize(evidence.getRepresentativeMessage()),
                    sanitize(evidence.getRepresentativeStackTrace()), sanitizedSamples);
            List<InfoContext> infoContext = new ArrayList<>();
            for (InfoContext fragment : evidence.getInfoContext()) {
                infoContext.add(new InfoContext(fragment.sampleEventId(), fragment.timestamp(),
                        sanitize(fragment.fileName()), fragment.startLine(), sanitize(fragment.content())));
            }
            return new SanitizedAiEvidence(sanitized.withInfoContext(
                    infoContext, sanitize(evidence.getInfoContextStatus())));
        } catch (RuntimeException ex) {
            // 失败即闭合：终止外发，且不得让敏感原文进入异常消息或日志。
            throw new AiEvidenceSanitizationException(evidence.getIssueId(), ex);
        }
    }

    /** 单字段脱敏；null 值保持为 null。 */
    private String sanitize(String value) {
        return sanitizer.sanitize(value);
    }
}
