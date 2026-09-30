package io.github.badfisher.ailog.analysis.ai;

import io.github.badfisher.ailog.domain.text.Sha256;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence.EvidenceSample;
import io.github.badfisher.ailog.domain.ai.SanitizedAiEvidence;

/**
 * AI 证据哈希生成器：为脱敏证据计算稳定 evidenceHash，作为分析复用与 Token 节约的基础。
 *
 * <p>哈希输入为明确声明的字段集合与固定顺序（对齐设计规范 §11.6）：
 * 稳定指纹、根因分类、消息模板、代表性消息与栈、全部样本的文本内容、
 * Prompt 版本与脱敏器版本。序列化采用「字段名=长度:内容;」的规范形式，
 * 长度前缀消除字段边界歧义，禁止对 {@code Object.toString()} 直接求哈希。</p>
 *
 * <p>刻意排除的字段：事件 ID、日志时间、窗口事件数、首次/最近出现时间、
 * Issue 标识、环境/系统/模块、分类器与规则集版本、命中规则 ID。
 * 纯次数漂移、时间窗口变化或同内容跨 Issue 不会改变哈希，
 * 保证相同问题、相同证据不重复消耗 AI 调用；
 * 实质内容（Family身份、分类、消息、栈、样本、Prompt 或脱敏版本）变化必然改变哈希。</p>
 */
public final class EvidenceHashGenerator {

    /** 哈希输入格式版本：参与序列化前缀，字段集合或格式演进时必须升级。 */
    public static final String SCHEMA_VERSION = "ev-v3";

    /**
     * 计算脱敏证据的 evidenceHash。
     *
     * @param evidence        已脱敏证据，非空
     * @param promptVersion   Prompt 版本，非空
     * @param sanitizerVersion 脱敏器版本，非空
     * @return 小写十六进制 SHA-256 摘要
     */
    public String hash(SanitizedAiEvidence evidence, String promptVersion,
            String sanitizerVersion) {
        if (evidence == null) {
            throw new IllegalArgumentException("脱敏证据不能为空");
        }
        if (promptVersion == null || promptVersion.isEmpty()) {
            throw new IllegalArgumentException("Prompt版本不能为空");
        }
        if (sanitizerVersion == null || sanitizerVersion.isEmpty()) {
            throw new IllegalArgumentException("脱敏器版本不能为空");
        }
        String canonical = canonicalize(evidence.getEvidence(), promptVersion, sanitizerVersion);
        return Sha256.sha256(canonical);
    }

    /** 按固定字段顺序构建规范化字符串，样本按列表顺序展开且数量参与哈希。 */
    private static String canonicalize(AiIssueEvidence evidence, String promptVersion,
            String sanitizerVersion) {
        StringBuilder canonical = new StringBuilder(1024);
        canonical.append(SCHEMA_VERSION).append(';');
        appendField(canonical, "stableFingerprint", evidence.getStableFingerprint());
        appendField(canonical, "fingerprintVersion", evidence.getFingerprintVersion());
        appendField(canonical, "rootCauseCategory", evidence.getRootCauseCategory());
        appendField(canonical, "messageTemplate", evidence.getMessageTemplate());
        appendField(canonical, "representativeMessage", evidence.getRepresentativeMessage());
        appendField(canonical, "representativeStackTrace", evidence.getRepresentativeStackTrace());
        appendField(canonical, "sampleCount", String.valueOf(evidence.getSamples().size()));
        for (EvidenceSample sample : evidence.getSamples()) {
            appendField(canonical, "sample.matchType", sample.getMatchType());
            appendField(canonical, "sample.truncated", String.valueOf(sample.isTruncated()));
            appendField(canonical, "sample.exceptionClass", sample.getExceptionClass());
            appendField(canonical, "sample.exceptionMessage", sample.getExceptionMessage());
            appendField(canonical, "sample.rootCauseException", sample.getRootCauseException());
            appendField(canonical, "sample.rootCauseMessage", sample.getRootCauseMessage());
            appendField(canonical, "sample.normalizedMessage", sample.getNormalizedMessage());
            appendField(canonical, "sample.simplifiedStack", sample.getSimplifiedStack());
            appendField(canonical, "sample.sampleContent", sample.getSampleContent());
            appendField(canonical, "sample.sampleContentTruncated",
                    String.valueOf(sample.isSampleContentTruncated()));
        }
        appendField(canonical, "infoContextStatus", evidence.getInfoContextStatus());
        for (io.github.badfisher.ailog.domain.ai.InfoContext context : evidence.getInfoContext()) {
            appendField(canonical, "info.file", context.fileName());
            appendField(canonical, "info.content", context.content());
        }
        appendField(canonical, "promptVersion", promptVersion);
        appendField(canonical, "sanitizerVersion", sanitizerVersion);
        return canonical.toString();
    }

    /** 追加「字段名=长度:内容;」；null 值使用显式 null 标记，与空串和字面量 "null" 无歧义。 */
    private static void appendField(StringBuilder canonical, String field, String value) {
        canonical.append(field).append('=');
        if (value == null) {
            canonical.append("null;");
        } else {
            canonical.append(value.length()).append(':').append(value).append(';');
        }
    }
}
