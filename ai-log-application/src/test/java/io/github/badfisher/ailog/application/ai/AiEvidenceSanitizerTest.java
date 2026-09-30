package io.github.badfisher.ailog.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.ai.AiIssueEvidence;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence.EvidenceSample;
import io.github.badfisher.ailog.domain.ai.SanitizedAiEvidence;
import io.github.badfisher.ailog.parser.security.SensitiveLogSanitizer;

/**
 * AI 证据脱敏网关测试：全字段脱敏副本、原文不变、失败闭合与异常不泄漏内容。
 */
class AiEvidenceSanitizerTest {

    private static final String SECRET_TEXT = "password=leak-123 手机 13800138000";

    private final SensitiveLogSanitizer logSanitizer = new SensitiveLogSanitizer();
    private final AiEvidenceSanitizer gateway = new AiEvidenceSanitizer(logSanitizer);

    @Test
    void sanitizesAllTextFieldsAndKeepsOriginalUntouched() {
        AiIssueEvidence evidence = evidence(SECRET_TEXT, SECRET_TEXT);

        SanitizedAiEvidence sanitized = gateway.sanitize(evidence);

        AiIssueEvidence copy = sanitized.getEvidence();
        assertThat(copy.getMessageTemplate()).doesNotContain("leak-123", "13800138000");
        assertThat(copy.getRepresentativeMessage()).doesNotContain("leak-123", "13800138000");
        assertThat(copy.getRepresentativeStackTrace()).doesNotContain("leak-123", "13800138000");
        assertThat(copy.getSamples()).hasSize(1);
        assertThat(copy.getSamples().get(0).getExceptionMessage())
                .doesNotContain("leak-123", "13800138000");
        assertThat(copy.getSamples().get(0).getSimplifiedStack())
                .doesNotContain("leak-123", "13800138000");
        // 原始证据不受脱敏影响，内部处理仍保留完整原文。
        assertThat(evidence.getMessageTemplate()).isEqualTo(SECRET_TEXT);
        assertThat(evidence.getSamples().get(0).getExceptionMessage()).isEqualTo(SECRET_TEXT);
        // 结构化字段原样保留。
        assertThat(copy.getIssueId()).isEqualTo(88L);
        assertThat(copy.getOccurrenceCount()).isEqualTo(12L);
        assertThat(copy.getMatchedRuleId()).isNull();
        assertThat(copy.getFirstOccurredAt()).isEqualTo(time(8));
    }

    @Test
    void keepsNullTextFieldsAsNull() {
        AiIssueEvidence evidence = evidence(null, null);

        SanitizedAiEvidence sanitized = gateway.sanitize(evidence);

        assertThat(sanitized.getEvidence().getMessageTemplate()).isNull();
        assertThat(sanitized.getEvidence().getRepresentativeMessage()).isNull();
        assertThat(sanitized.getEvidence().getSamples().get(0).getExceptionMessage()).isNull();
    }

    @Test
    void sanitizeFailureFailsClosedWithoutLeakingContent() {
        AiEvidenceSanitizer failingGateway = new AiEvidenceSanitizer(value -> {
            throw new IllegalStateException("regex engine failure");
        });
        AiIssueEvidence evidence = evidence(SECRET_TEXT, SECRET_TEXT);

        assertThatThrownBy(() -> failingGateway.sanitize(evidence))
                .isInstanceOf(AiEvidenceSanitizationException.class)
                .hasMessageContaining("88")
                .hasMessageNotContaining("leak-123")
                .hasMessageNotContaining("13800138000");
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new AiEvidenceSanitizer((SensitiveLogSanitizer) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.sanitize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sanitizesJsonMessagesAndBearerStacksWithoutMutatingEvidence() {
        String message = "{\"apiKey\":\"evidence-key\",\"code\":500}";
        String stack = "Authorization: Bearer stack-credential";
        AiIssueEvidence original = evidence(message, stack);

        AiIssueEvidence sanitized = gateway.sanitize(original).getEvidence();

        assertThat(sanitized.getRepresentativeMessage()).doesNotContain("evidence-key");
        assertThat(sanitized.getRepresentativeStackTrace()).doesNotContain("stack-credential");
        assertThat(sanitized.getSamples().get(0).getExceptionMessage())
                .doesNotContain("evidence-key");
        assertThat(original.getRepresentativeMessage()).isEqualTo(message);
        assertThat(original.getRepresentativeStackTrace()).isEqualTo(stack);
        assertThat(sanitized.getIssueId()).isEqualTo(original.getIssueId());
        assertThat(sanitized.getOccurrenceCount()).isEqualTo(original.getOccurrenceCount());
    }

    @Test
    void sanitizeFailureDoesNotLeakTheOriginalCauseThroughItsStackTrace() {
        AiEvidenceSanitizer failingGateway = new AiEvidenceSanitizer(value -> {
            IllegalStateException failure = new IllegalStateException(SECRET_TEXT,
                    new IllegalArgumentException("Authorization: Bearer nested-secret"));
            failure.addSuppressed(new IllegalStateException("suppressed-secret"));
            throw failure;
        });
        AiIssueEvidence original = evidence(SECRET_TEXT, SECRET_TEXT);

        AiEvidenceSanitizationException failure = assertThrows(
                AiEvidenceSanitizationException.class, () -> failingGateway.sanitize(original));

        StringWriter stackTrace = new StringWriter();
        failure.printStackTrace(new PrintWriter(stackTrace));
        assertThat(stackTrace.toString())
                .contains("88", "IllegalStateException")
                .doesNotContain("leak-123", "13800138000", "nested-secret", "suppressed-secret");
        assertThat(failure.getCause()).isNull();
        assertThat(original.getRepresentativeMessage()).isEqualTo(SECRET_TEXT);
    }

    private static LocalDateTime time(int hour) {
        return LocalDateTime.of(2026, 8, 25, hour, 0);
    }

    private static AiIssueEvidence evidence(String message, String stack) {
        EvidenceSample sample = new EvidenceSample(3001L, time(8), "STRICT_ERROR", false,
                "java.lang.IllegalStateException", message, "java.lang.NullPointerException",
                "value is null", message, stack);
        return new AiIssueEvidence(88L, "prod", "demo", "order", "stable-fp", "fp-v2-mt-v1", "CODE",
                "MQ", "java.lang.IllegalStateException", "java.lang.NullPointerException",
                "com.badfisher.order.OrderService", "createOrder", message, null,
                12L, time(8), time(20), message, stack,
                Arrays.asList(sample));
    }
}
