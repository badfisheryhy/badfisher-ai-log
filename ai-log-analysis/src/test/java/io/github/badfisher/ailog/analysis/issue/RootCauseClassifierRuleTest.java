package io.github.badfisher.ailog.analysis.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.issue.ExceptionStructure;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.issue.RootCauseDecision;
import io.github.badfisher.ailog.domain.issue.RootCauseRule;
import io.github.badfisher.ailog.domain.issue.RootCauseRuleType;
import io.github.badfisher.ailog.domain.log.LogEvent;
import io.github.badfisher.ailog.domain.log.LogLocationMode;

/** 数据库规则快照驱动的分类瀑布测试。 */
class RootCauseClassifierRuleTest {

    @Test
    void customKeywordRuleWinsOverDefaultUnknown() {
        List<RootCauseRule> rules = Collections.singletonList(rule(
                RootCauseCategory.BUSINESS, RootCauseRuleType.KEYWORD, "gateway timeout", 10));

        assertThat(new RootCauseClassifier(rules)
                .classify(structure("java.lang.RuntimeException", "GATEWAY TIMEOUT after 5s")))
                .isEqualTo(RootCauseCategory.BUSINESS);
        assertThat(new RootCauseClassifier()
                .classify(structure("java.lang.RuntimeException", "gateway timeout after 5s")))
                .isEqualTo(RootCauseCategory.UNKNOWN);
    }

    @Test
    void rulesExecuteInPriorityThenIdOrderAndFirstMatchWins() {
        RootCauseRule lowerPriority = ruleWithId(
                100L, RootCauseCategory.EXTERNAL_SERVICE, RootCauseRuleType.KEYWORD, "payment", 10);
        RootCauseRule higherPriority = ruleWithId(
                10L, RootCauseCategory.BUSINESS, RootCauseRuleType.KEYWORD, "payment", 10);
        RootCauseRule laterPriority = ruleWithId(
                1L, RootCauseCategory.BUSINESS, RootCauseRuleType.KEYWORD, "payment", 20);
        RootCauseClassifier classifier = new RootCauseClassifier(
                Arrays.asList(laterPriority, lowerPriority, higherPriority));

        assertThat(classifier.classify(structure("java.lang.RuntimeException", "payment failed")))
                .isEqualTo(RootCauseCategory.BUSINESS);
    }

    @Test
    void regexRuleMatchesCaseInsensitively() {
        List<RootCauseRule> rules = Collections.singletonList(rule(
                RootCauseCategory.INFRASTRUCTURE, RootCauseRuleType.REGEX,
                "node\\s+\\d+\\s+unreachable", 10));

        assertThat(new RootCauseClassifier(rules)
                .classify(structure("java.lang.RuntimeException", "NODE 7 UNREACHABLE")))
                .isEqualTo(RootCauseCategory.INFRASTRUCTURE);
    }

    @Test
    void emptyRulesFallThroughToUnknown() {
        assertThat(new RootCauseClassifier(Collections.<RootCauseRule>emptyList())
                .classify(structure("java.lang.RuntimeException", "库存不足")))
                .isEqualTo(RootCauseCategory.UNKNOWN);
    }

    @Test
    void unmatchedRulesRemainUnknown() {
        List<RootCauseRule> rules = Collections.singletonList(rule(
                RootCauseCategory.DATABASE, RootCauseRuleType.KEYWORD, "not-in-text", 10));

        assertThat(new RootCauseClassifier(rules)
                .classify(structure("java.lang.RuntimeException", "库存不足")))
                .isEqualTo(RootCauseCategory.UNKNOWN);
        assertThat(new RootCauseClassifier(rules)
                .classify(structure("com.badfisher.lk.wms.exception.StockException", "boom")))
                .isEqualTo(RootCauseCategory.UNKNOWN);
    }

    @Test
    void invalidRegexFailsFastWithPatternInMessage() {
        List<RootCauseRule> rules = Collections.singletonList(rule(
                RootCauseCategory.DATABASE, RootCauseRuleType.REGEX, "[unclosed", 10));

        assertThatThrownBy(() -> new RootCauseClassifier(rules))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[unclosed");
    }

    @Test
    void matcherTextIncludesRootCauseAndExceptionMessagesAndHeader() {
        RootCauseClassifier classifier = new RootCauseClassifier(Collections.singletonList(rule(
                RootCauseCategory.DATABASE, RootCauseRuleType.KEYWORD, "java.sql.batchupdateexception",
                10)));
        RootCauseDecision decision = classifier.decide(
                structure("java.sql.BatchUpdateException", "insert failed"), event("数据库回放失败"));

        assertThat(decision.getCategory()).isEqualTo(RootCauseCategory.DATABASE);
        assertThat(decision.isExpected()).isFalse();
    }

    @Test
    void productionCategoryRulesClassifyExceptionStructuresAndBusinessKeywords() {
        RootCauseClassifier classifier = new RootCauseClassifier(Arrays.asList(
                rule(RootCauseCategory.DATABASE, RootCauseRuleType.REGEX,
                        "(?:java\\.sql\\.|com\\.mysql\\.|org\\.hibernate\\.)", 10),
                rule(RootCauseCategory.EXTERNAL_SERVICE, RootCauseRuleType.REGEX,
                        "(?:java\\.net\\.connectexception|java\\.net\\.sockettimeoutexception)",
                        11),
                rule(RootCauseCategory.INFRASTRUCTURE, RootCauseRuleType.REGEX,
                        "(?:org\\.redisson\\.|io\\.lettuce\\.|redis\\.clients\\.)", 12),
                rule(RootCauseCategory.CODE, RootCauseRuleType.REGEX,
                        "(?:nullpointerexception|classcastexception|"
                                + "indexoutofboundsexception|assertionerror)", 13),
                rule(RootCauseCategory.BUSINESS, RootCauseRuleType.KEYWORD,
                        "库存不足", 100)));

        assertThat(classifier.classify(structure("java.sql.SQLException", "failed")))
                .isEqualTo(RootCauseCategory.DATABASE);
        assertThat(classifier.classify(
                structure("java.net.SocketTimeoutException", "failed")))
                .isEqualTo(RootCauseCategory.EXTERNAL_SERVICE);
        assertThat(classifier.classify(
                structure("org.redisson.client.RedisException", "failed")))
                .isEqualTo(RootCauseCategory.INFRASTRUCTURE);
        assertThat(classifier.classify(
                structure("java.lang.NullPointerException", "failed")))
                .isEqualTo(RootCauseCategory.CODE);
        assertThat(classifier.classify(
                structure("java.lang.RuntimeException", "库存不足")))
                .isEqualTo(RootCauseCategory.BUSINESS);
        assertThat(classifier.classify(
                structure("java.lang.RuntimeException", "unmatched")))
                .isEqualTo(RootCauseCategory.UNKNOWN);
    }

    @Test
    void decisionRecordsMatchedRule() {
        RootCauseRule rule = new RootCauseRule(Long.valueOf(42L), "default",
                RootCauseCategory.BUSINESS, RootCauseRuleType.KEYWORD, "payment", 10);
        RootCauseClassifier classifier = new RootCauseClassifier(
                Collections.singletonList(rule));

        RootCauseDecision decision = classifier.decide(
                structure("java.lang.RuntimeException", "payment failed"), null);

        assertThat(decision.getCategory()).isEqualTo(RootCauseCategory.BUSINESS);
        assertThat(decision.getMatchedRuleId()).isEqualTo(Long.valueOf(42L));
        assertThat(decision.isExpected()).isFalse();
    }

    @Test
    void suppressRuleMarksDecisionExpectedWithoutChangingCategory() {
        RootCauseRule rule = new RootCauseRule(Long.valueOf(43L), "default",
                RootCauseCategory.BUSINESS, RootCauseRuleType.KEYWORD,
                "已存在退款", 10, true, "REFUND_ALREADY_EXISTS", "DUPLICATE_REQUEST");
        RootCauseClassifier classifier = new RootCauseClassifier(Collections.singletonList(rule));

        RootCauseDecision decision = classifier.decide(
                structure("java.lang.RuntimeException", "已存在退款"), null);

        assertThat(decision.getCategory()).isEqualTo(RootCauseCategory.BUSINESS);
        assertThat(decision.isExpected()).isTrue();
        assertThat(decision.getMatchedRuleId()).isEqualTo(Long.valueOf(43L));
        assertThat(decision.getReasonCode()).isEqualTo("DUPLICATE_REQUEST");
    }

    @Test
    void exactProductionWordingForSyncRule() {
        RootCauseRule rule = new RootCauseRule(Long.valueOf(47L), "default",
                RootCauseCategory.BUSINESS, RootCauseRuleType.KEYWORD,
                "同步通途失败", 20, false, null, null);
        RootCauseClassifier classifier = new RootCauseClassifier(
                Collections.singletonList(rule));

        RootCauseDecision actual = classifier.decide(
                structure("java.lang.RuntimeException", "调拨单同步通途失败"), null);
        RootCauseDecision misspelled = classifier.decide(
                structure("java.lang.RuntimeException", "调拨单同步通道失败"), null);

        assertThat(actual.getCategory()).isEqualTo(RootCauseCategory.BUSINESS);
        assertThat(actual.getMatchedRuleId()).isEqualTo(Long.valueOf(47L));
        assertThat(misspelled.getMatchedRuleId()).isNull();
    }

    @Test
    void keywordClassificationDoesNotDependOnTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            RootCauseClassifier classifier = new RootCauseClassifier(Collections.singletonList(
                    rule(RootCauseCategory.CODE, RootCauseRuleType.KEYWORD,
                            "illegalstateexception", 10)));

            assertThat(classifier.classify(
                    structure("java.lang.IllegalStateException", "failure")))
                    .isEqualTo(RootCauseCategory.CODE);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void legacyExpectedDecisionDoesNotRequestAiAnalysis() {
        RootCauseDecision decision = new RootCauseDecision(RootCauseCategory.BUSINESS,
                Long.valueOf(42L), true, "EXPECTED");

        assertThat(decision.isExpected()).isTrue();
        assertThat(decision.isAiRequired()).isFalse();
        assertThat(decision.getMatchedRuleId()).isEqualTo(Long.valueOf(42L));
    }

    @Test
    void legacyUnexpectedDecisionStillRequestsAiAnalysis() {
        RootCauseDecision decision = new RootCauseDecision(RootCauseCategory.CODE,
                Long.valueOf(43L), false);

        assertThat(decision.isExpected()).isFalse();
        assertThat(decision.isAiRequired()).isTrue();
    }

    private static RootCauseRule rule(RootCauseCategory category, RootCauseRuleType type,
            String pattern, int priority) {
        return new RootCauseRule("default", category, type, pattern, priority);
    }

    private static RootCauseRule ruleWithId(Long id, RootCauseCategory category, RootCauseRuleType type,
            String pattern, int priority) {
        return new RootCauseRule(id, "default", category, type, pattern, priority);
    }

    private static ExceptionStructure structure(String exceptionClass, String message) {
        return new ExceptionStructure(
                exceptionClass, message, exceptionClass, message,
                null, null, null, null, null, null,
                Collections.<String>emptyList());
    }

    private static LogEvent event(String headerMessage) {
        String content = "2026-08-25 10:00:00,000 [main] ERROR Demo - " + headerMessage;
        return new LogEvent(Instant.parse("2026-08-25T02:00:00Z"), "ERROR", "main", null,
                null, null, "Demo", "run", Integer.valueOf(10), headerMessage, content,
                1L, 1L, 0L, content.length(), LogLocationMode.PLAIN_BYTE_OFFSET, false);
    }
}
