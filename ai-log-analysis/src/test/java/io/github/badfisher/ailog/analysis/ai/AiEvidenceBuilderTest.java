package io.github.badfisher.ailog.analysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.ai.AiIssueEvidence;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence.EvidenceSample;
import io.github.badfisher.ailog.domain.analysis.ActionableIssueSnapshot;
import io.github.badfisher.ailog.domain.analysis.RepresentativeEvent;

/**
 * AI 证据构建器测试：样本边界、字段截断、代表性消息来源与参数校验。
 */
class AiEvidenceBuilderTest {

    @Test
    void buildsEvidenceWithBoundedSamplesAndTruncatedFields() {
        AiEvidenceBuilder builder = new AiEvidenceBuilder(2, 50, 100);
        List<RepresentativeEvent> samples = Arrays.asList(
                event(1L, repeat('b', 80), stack('a', 200)),
                event(2L, "m-2", stack('c', 30)),
                event(3L, "m-3", stack('d', 200)));

        AiIssueEvidence evidence = builder.build(snapshot(), samples);

        // 样本上限 2：保留质量排序最前的两条。
        assertThat(evidence.getSamples()).hasSize(2);
        assertThat(evidence.getSamples()).extracting(EvidenceSample::getEventId)
                .containsExactly(1L, 2L);
        EvidenceSample first = evidence.getSamples().get(0);
        // 消息超过 50 字符截断到 50；栈超过 100 字符截断到 100，均保留前缀。
        assertThat(first.getExceptionMessage()).hasSize(50).startsWith("b");
        assertThat(first.getSimplifiedStack()).hasSize(100).startsWith("a");
        assertThat(evidence.getSamples().get(1).getSimplifiedStack()).hasSize(30)
                .startsWith("c");
        // Issue 事实字段完整映射。
        assertThat(evidence.getIssueId()).isEqualTo(88L);
        assertThat(evidence.getEnvironment()).isEqualTo("prod");
        assertThat(evidence.getSystemCode()).isEqualTo("demo");
        assertThat(evidence.getModuleCode()).isEqualTo("order");
        assertThat(evidence.getStableFingerprint()).isEqualTo("stable-fp");
        assertThat(evidence.getFingerprintVersion()).isEqualTo("fp-v2-mt-v1");
        assertThat(evidence.getRootCauseCategory()).isEqualTo("CODE");
        assertThat(evidence.getTriggerChannel()).isEqualTo("MQ");
        assertThat(evidence.getExceptionClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(evidence.getRootCauseException()).isEqualTo("java.lang.NullPointerException");
        assertThat(evidence.getBusinessClass()).isEqualTo("com.badfisher.order.OrderService");
        assertThat(evidence.getBusinessMethod()).isEqualTo("createOrder");
        assertThat(evidence.getMessageTemplate()).isEqualTo("create order failed for {id}");
        assertThat(evidence.getMatchedRuleId()).isNull();
        assertThat(evidence.getOccurrenceCount()).isEqualTo(12L);
        assertThat(evidence.getFirstOccurredAt()).isEqualTo(time(8));
        assertThat(evidence.getLastOccurredAt()).isEqualTo(time(20));
    }

    @Test
    void keepsAllSamplesWhenWithinLimit() {
        AiEvidenceBuilder builder = new AiEvidenceBuilder(3, 4096, 4096);

        AiIssueEvidence evidence = builder.build(snapshot(), Arrays.asList(
                event(1L, "m-1", "s-1"), event(2L, "m-2", "s-2")));

        assertThat(evidence.getSamples()).extracting(EvidenceSample::getEventId)
                .containsExactly(1L, 2L);
    }

    @Test
    void firstRankedSampleBecomesRepresentative() {
        AiEvidenceBuilder builder = new AiEvidenceBuilder(5, 4096, 4096);

        AiIssueEvidence evidence = builder.build(snapshot(), Arrays.asList(
                event(1L, "m-1", "s-1"), event(2L, "m-2", "s-2"), event(3L, "m-3", "s-3")));

        assertThat(evidence.getRepresentativeMessage()).isEqualTo("m-1");
        assertThat(evidence.getRepresentativeStackTrace()).isEqualTo("s-1");
    }

    @Test
    void fallsBackToSnapshotLatestFieldsWhenNoSamples() {
        AiEvidenceBuilder builder = new AiEvidenceBuilder(3, 4096, 4096);

        AiIssueEvidence evidence = builder.build(snapshot(),
                Collections.<RepresentativeEvent>emptyList());

        assertThat(evidence.getSamples()).isEmpty();
        assertThat(evidence.getRepresentativeMessage()).isEqualTo("latest-message");
        assertThat(evidence.getRepresentativeStackTrace()).isEqualTo("latest-stack");
    }

    @Test
    void singleSampleLimitKeepsBestRankedEvidence() {
        AiEvidenceBuilder builder = new AiEvidenceBuilder(1, 4096, 4096);

        AiIssueEvidence evidence = builder.build(snapshot(), Arrays.asList(
                event(1L, "m-1", "s-1"), event(2L, "m-2", "s-2"), event(3L, "m-3", "s-3")));

        assertThat(evidence.getSamples()).extracting(EvidenceSample::getEventId)
                .containsExactly(1L);
        assertThat(evidence.getRepresentativeMessage()).isEqualTo("m-1");
    }

    @Test
    void repeatedBuildProducesIdenticalFacts() {
        AiEvidenceBuilder builder = new AiEvidenceBuilder(3, 4096, 4096);
        List<RepresentativeEvent> samples = Arrays.asList(event(1L, "m-1", "s-1"),
                event(2L, "m-2", "s-2"));

        AiIssueEvidence first = builder.build(snapshot(), samples);
        AiIssueEvidence second = builder.build(snapshot(), samples);

        assertThat(second.getSamples()).extracting(EvidenceSample::getEventId)
                .isEqualTo(first.getSamples().stream()
                        .map(sample -> Long.valueOf(sample.getEventId()))
                        .collect(java.util.stream.Collectors.toList()));
        assertThat(second.getRepresentativeMessage()).isEqualTo(first.getRepresentativeMessage());
        assertThat(second.getRepresentativeStackTrace())
                .isEqualTo(first.getRepresentativeStackTrace());
        assertThat(second.getMessageTemplate()).isEqualTo(first.getMessageTemplate());
        assertThat(second.getOccurrenceCount()).isEqualTo(first.getOccurrenceCount());
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new AiEvidenceBuilder(0, 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiEvidenceBuilder(3, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiEvidenceBuilder(3, 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
        AiEvidenceBuilder builder = new AiEvidenceBuilder();
        assertThatThrownBy(() -> builder.build(null, new ArrayList<RepresentativeEvent>()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(snapshot(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesRepositoryRankingWithoutMutatingSource() {
        List<RepresentativeEvent> samples = Collections.unmodifiableList(Arrays.asList(
                event(30L, time(20), "latest", "latest-stack"),
                event(10L, time(8), "first", "first-stack"),
                event(20L, time(12), "middle", "middle-stack")));

        AiIssueEvidence evidence = new AiEvidenceBuilder(2, 4096, 4096)
                .build(snapshot(), samples);

        assertThat(evidence.getSamples()).extracting(EvidenceSample::getEventId)
                .containsExactly(30L, 10L);
        assertThat(evidence.getRepresentativeMessage()).isEqualTo("latest");
        assertThat(evidence.getRepresentativeStackTrace()).isEqualTo("latest-stack");
        assertThat(samples).extracting(RepresentativeEvent::getEventId)
                .containsExactly(30L, 10L, 20L);
    }

    @Test
    void doesNotRerankEvidenceByTimestamp() {
        List<RepresentativeEvent> samples = Arrays.asList(
                event(3L, time(8), "third", "third-stack"),
                event(1L, null, "unknown-time", "unknown-stack"),
                event(2L, time(8), "second", "second-stack"));

        AiIssueEvidence all = new AiEvidenceBuilder(3, 4096, 4096).build(snapshot(), samples);
        AiIssueEvidence latest = new AiEvidenceBuilder(1, 4096, 4096).build(snapshot(), samples);

        assertThat(all.getSamples()).extracting(EvidenceSample::getEventId)
                .containsExactly(3L, 1L, 2L);
        assertThat(latest.getSamples()).extracting(EvidenceSample::getEventId)
                .containsExactly(3L);
        assertThat(latest.getRepresentativeMessage()).isEqualTo("third");
    }

    private static LocalDateTime time(int hour) {
        return LocalDateTime.of(2026, 8, 25, hour, 0);
    }

    private static ActionableIssueSnapshot snapshot() {
        return new ActionableIssueSnapshot(88L, "prod", "demo", "order", "stable-fp", "fp-v2-mt-v1",
                "CODE", "MQ", "java.lang.IllegalStateException", "java.lang.NullPointerException",
                "com.badfisher.order.OrderService", "createOrder", "create order failed for {id}",
                null, 12L, time(8), time(20), 3001L,
                "latest-message", "latest-stack");
    }

    private static RepresentativeEvent event(long eventId, String message, String stack) {
        return event(eventId, time(8), message, stack);
    }

    private static RepresentativeEvent event(long eventId, LocalDateTime logTime,
            String message, String stack) {
        return new RepresentativeEvent(eventId, 1000L, logTime, "STRICT_ERROR", "LINE", 120L,
                false, "http-nio-8080-exec-1", null, null, null,
                "java.lang.IllegalStateException", message, "java.lang.NullPointerException",
                "value is null", "com.badfisher.order.OrderService", "createOrder", 88, message,
                stack);
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static String stack(char value, int count) {
        return repeat(value, count);
    }
}
