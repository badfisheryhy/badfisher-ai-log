package io.github.badfisher.ailog.analysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.ai.AiIssueEvidence;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence.EvidenceSample;
import io.github.badfisher.ailog.domain.ai.SanitizedAiEvidence;

/**
 * AI 证据哈希生成器测试：相同证据稳定、实质变化敏感、无意义字段不敏感。
 */
class EvidenceHashGeneratorTest {

    private static final String PROMPT = "ai-issue-v1";

    private static final String SANITIZER = "sanitizer-v1";

    private final EvidenceHashGenerator generator = new EvidenceHashGenerator();

    @Test
    void sameEvidenceProducesSameHash() {
        String first = generator.hash(sanitized(evidence()), PROMPT, SANITIZER);
        String second = generator.hash(sanitized(evidence()), PROMPT, SANITIZER);

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
        // 同一对象重复计算也必须稳定。
        SanitizedAiEvidence evidence = sanitized(evidence());
        assertThat(generator.hash(evidence, PROMPT, SANITIZER))
                .isEqualTo(generator.hash(evidence, PROMPT, SANITIZER));
    }

    @Test
    void changedEvidenceProducesDifferentHash() {
        // 消息模板变化。
        assertThat(generator.hash(sanitized(evidence("changed template", "NPE", "stack-a")),
                PROMPT, SANITIZER))
                        .isNotEqualTo(generator.hash(
                                sanitized(evidence("origin template", "NPE", "stack-a")), PROMPT,
                                SANITIZER));
        // 代表性栈变化。
        assertThat(generator.hash(sanitized(evidence("origin template", "NPE", "stack-b")),
                PROMPT, SANITIZER))
                        .isNotEqualTo(generator.hash(
                                sanitized(evidence("origin template", "NPE", "stack-a")), PROMPT,
                                SANITIZER));
        // Prompt 或脱敏器版本变化都会使旧结论不可复用。
        SanitizedAiEvidence evidence = sanitized(evidence());
        assertThat(generator.hash(evidence, "ai-issue-v2", SANITIZER))
                .isNotEqualTo(generator.hash(evidence, PROMPT, SANITIZER));
        assertThat(generator.hash(evidence, PROMPT, "sanitizer-v2"))
                .isNotEqualTo(generator.hash(evidence, PROMPT, SANITIZER));
        // 样本内容变化。
        assertThat(generator.hash(
                sanitized(evidence("origin template", "NPE", "stack-a", "sample-message-a")),
                PROMPT, SANITIZER))
                        .isNotEqualTo(generator.hash(
                                sanitized(evidence("origin template", "NPE", "stack-a",
                                        "sample-message-b")), PROMPT, SANITIZER));
    }

    @Test
    void meaninglessChangesKeepHashStable() {
        // 窗口事件数、出现时间、Issue 标识、环境维度、样本事件 ID 变化不影响哈希。
        AiIssueEvidence drifted = new AiIssueEvidence(999L, "test", "crm", "other", "stable-fp",
                "fp-v2-mt-v1", "CODE", "MQ", "java.lang.IllegalStateException",
                "java.lang.NullPointerException", "com.badfisher.order.OrderService", "createOrder",
                "origin template", null, 9999L,
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 2, 0, 0), "NPE",
                "stack-a", samples(7777L, "sample-message"));

        assertThat(generator.hash(sanitized(evidence()), PROMPT, SANITIZER))
                .isEqualTo(generator.hash(sanitized(drifted), PROMPT, SANITIZER));
    }

    @Test
    void lengthPrefixPreventsFieldBoundaryAmbiguity() {
        // 模板 "ab" + 代表性消息 "" 与 模板 "a" + 代表性消息 "b" 必须产生不同哈希。
        AiIssueEvidence joined = evidence("ab", "", "stack-a");
        AiIssueEvidence split = evidence("a", "b", "stack-a");

        assertThat(generator.hash(sanitized(joined), PROMPT, SANITIZER))
                .isNotEqualTo(generator.hash(sanitized(split), PROMPT, SANITIZER));
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> generator.hash(null, PROMPT, SANITIZER))
                .isInstanceOf(IllegalArgumentException.class);
        SanitizedAiEvidence evidence = sanitized(evidence());
        assertThatThrownBy(() -> generator.hash(evidence, null, SANITIZER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.hash(evidence, PROMPT, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LocalDateTime time(int hour) {
        return LocalDateTime.of(2026, 8, 25, hour, 0);
    }

    private static SanitizedAiEvidence sanitized(AiIssueEvidence content) {
        return new SanitizedAiEvidence(content);
    }

    private static AiIssueEvidence evidence() {
        return evidence("origin template", "NPE", "stack-a");
    }

    private static AiIssueEvidence evidence(String template, String message, String stack) {
        return evidence(template, message, stack, "sample-message");
    }

    private static AiIssueEvidence evidence(String template, String message, String stack,
            String sampleMessage) {
        return new AiIssueEvidence(88L, "prod", "demo", "order", "stable-fp", "fp-v2-mt-v1", "CODE",
                "MQ", "java.lang.IllegalStateException", "java.lang.NullPointerException",
                "com.badfisher.order.OrderService", "createOrder", template, null,
                12L, time(8), time(20), message, stack,
                samples(3001L, sampleMessage));
    }

    private static List<EvidenceSample> samples(long eventId, String sampleMessage) {
        return Collections.singletonList(new EvidenceSample(eventId, time(8), "STRICT_ERROR",
                false, "java.lang.IllegalStateException", sampleMessage,
                "java.lang.NullPointerException", "value is null", sampleMessage, "stack-a"));
    }
}
