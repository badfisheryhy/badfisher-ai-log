package io.github.badfisher.ailog.application.analysis;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.analysis.SuppressRule;
import io.github.badfisher.ailog.domain.log.LogEvent;
import io.github.badfisher.ailog.domain.log.LogLocationMode;

import static org.assertj.core.api.Assertions.assertThat;

class SuppressionRuleMatcherTest {

    private final SuppressionRuleMatcher matcher = new SuppressionRuleMatcher();

    @Test
    void matchesWholeAndPartialKeywordIgnoringCase() {
        SuppressRule rule = new SuppressRule(1L, "demo", "sample-service", "order not found");

        assertThat(matcher.match(event("ORDER NOT FOUND", "irrelevant"),
                Collections.singletonList(rule))).isSameAs(rule);
        assertThat(matcher.match(event("Third-party ORDER NOT FOUND for id", "irrelevant"),
                Collections.singletonList(rule)).getId()).isEqualTo(1L);
        assertThat(matcher.match(event("other failure", "irrelevant"),
                Collections.singletonList(rule))).isNull();
    }

    @Test
    void fallbackUsesOnlyFirstContentLine() {
        SuppressRule rule = new SuppressRule(1L, "demo", "sample-service", "timeoutexception");
        LogEvent event = event(null, "business failed\njava.util.concurrent.TimeoutException");

        assertThat(matcher.match(event, Collections.singletonList(rule))).isNull();
        assertThat(matcher.match(event,
                Collections.singletonList(new SuppressRule(2L, "demo", "sample-service",
                        "business")))
                .getId()).isEqualTo(2L);
    }

    @Test
    void firstMatchingRuleWins() {
        SuppressRule first = new SuppressRule(2L, "demo", "sample-service", "not found");
        SuppressRule second = new SuppressRule(1L, "demo", "sample-service", "not found");

        SuppressRule match = matcher.match(event("order not found", ""),
                Arrays.asList(first, second));

        assertThat(match.getId()).isEqualTo(2L);
        assertThat(match.getModuleCode()).isEqualTo("sample-service");
    }

    private static LogEvent event(String message, String content) {
        return new LogEvent(Instant.EPOCH, "ERROR", null, null, null, null,
                null, null, null, message, content, 1L, 1L, 0L, 0L,
                LogLocationMode.PLAIN_BYTE_OFFSET, false);
    }
}
