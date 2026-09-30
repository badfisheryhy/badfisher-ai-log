package io.github.badfisher.ailog.analysis.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.issue.AnalyzedError;
import io.github.badfisher.ailog.domain.issue.ExceptionStructure;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.issue.RootCauseRule;
import io.github.badfisher.ailog.domain.issue.RootCauseRuleType;
import io.github.badfisher.ailog.domain.issue.TriggerChannel;
import io.github.badfisher.ailog.domain.log.LogEvent;
import io.github.badfisher.ailog.domain.log.LogLocationMode;

class IssueEngineComponentsTest {

    private static final RootCauseClassifier DEFAULT_CLASSIFIER = new RootCauseClassifier(Arrays.asList(
            new RootCauseRule("default", RootCauseCategory.DATABASE, RootCauseRuleType.KEYWORD,
                    "org.hibernate.exception.LockAcquisitionException", 10),
            new RootCauseRule("default", RootCauseCategory.EXTERNAL_SERVICE, RootCauseRuleType.KEYWORD,
                    "java.net.NoRouteToHostException", 10),
            new RootCauseRule("default", RootCauseCategory.INFRASTRUCTURE, RootCauseRuleType.KEYWORD,
                    "io.lettuce.core.ProtocolViolationException", 10),
            new RootCauseRule("default", RootCauseCategory.CODE, RootCauseRuleType.KEYWORD,
                    "java.lang.NullPointerException", 10),
            new RootCauseRule("default", RootCauseCategory.BUSINESS, RootCauseRuleType.KEYWORD,
                    "com.badfisher.lk.wms.exception.StockException", 10)));

    @Test
    void extractsAdvertisingAndBadfisherFramesWithoutSimilarPackagesOrProxies() {
        String advertising = "at com.example.analytics.AdService.save(AdService.java:42)";
        String badfisher = "at com.badfisher.OrderService.create(OrderService.java:10)";
        String content = "java.lang.IllegalStateException: failed\n"
                + "at com.example.analyticsx.Other.save(Other.java:1)\n"
                + "at com.badfisherx.Other.save(Other.java:2)\n"
                + "at com.example.analytics.AdService$$Enhancer.invoke(AdService.java:3)\n"
                + "at org.springframework.Proxy.invoke(Proxy.java:4)\n"
                + advertising + "\n" + badfisher;
        ExceptionStructureExtractor extractor = new ExceptionStructureExtractor(
                Arrays.asList("com.badfisher", "com.example.analytics"), 15);

        ExceptionStructure result = extractor.extract(headerlessEvent(content));

        assertThat(result.getBusinessClass()).isEqualTo("com.example.analytics.AdService");
        assertThat(result.getBusinessMethod()).isEqualTo("save");
        assertThat(result.getBusinessLine()).isEqualTo(42);
        assertThat(result.getBusinessFrames()).containsExactly(advertising, badfisher);
        assertThat(new StackSimplifier(4096).simplify(result)).contains(advertising, badfisher);

        ExceptionStructure limited = new ExceptionStructureExtractor(
                Arrays.asList("com.badfisher", "com.example.analytics"), 1)
                .extract(headerlessEvent(content));
        assertThat(limited.getBusinessFrames()).containsExactly(advertising);
    }

    @Test
    void keepsMqAsTriggerAndClassifiesNullPointerAsCode() {
        AnalyzedError result = processor().process(event(10, "order 123 failed"));

        assertThat(result.getCategory()).isEqualTo(RootCauseCategory.CODE);
        assertThat(result.getTriggerChannel()).isEqualTo(TriggerChannel.MQ);
        assertThat(result.getSimplifiedStack()).contains("com.badfisher.OrderService.create");
        assertThat(result.getFingerprintVersion()).isEqualTo(ErrorContentNormalizer.GROUPING_MARKER);
    }

    @Test
    void sampleKeyIgnoresDynamicNumberAndLine() {
        AnalyzedError first = processor().process(event(10, "order 123 failed"));
        AnalyzedError second = processor().process(event(99, "order 456 failed"));

        assertThat(first.getStableFingerprint()).isEqualTo(second.getStableFingerprint());
        assertThat(first.getStrictFingerprint()).isEqualTo(second.getStrictFingerprint());
    }

    @Test
    void differentHttpStatusMustNotMerge() {
        ErrorContentNormalizer generator = new ErrorContentNormalizer();

        assertThat(generator.normalize("upstream HTTP 500"))
                .isNotEqualTo(generator.normalize("upstream HTTP 503"));
    }

    @Test
    void normalizesDynamicIdsWithoutDestroyingSemanticCodes() {
        ErrorContentNormalizer generator = new ErrorContentNormalizer();

        assertThat(generator.normalize("订单 8839201 失败"))
                .isEqualTo(generator.normalize("订单 9923018 失败"));
        assertThat(generator.normalize("wx20260819abc"))
                .isEqualTo(generator.normalize("wx20260820xyz"));
        assertThat(generator.normalize("订单1234567890123456789失败"))
                .isEqualTo(generator.normalize("订单9876543210987654321失败"));
        assertThat(generator.normalize("5 未找到对应记录"))
                .isEqualTo(generator.normalize("70 未找到对应记录"));
        assertThat(generator.normalize("[wx20260819abc]"))
                .isEqualTo("[{id}]");
        assertThat(generator.normalize("[CANCELLED]"))
                .isEqualTo("[CANCELLED]");
        assertThat(generator.normalize("[FAILED]"))
                .isEqualTo("[FAILED]");
        assertThat(generator.normalize("HTTP 500"))
                .isNotEqualTo(generator.normalize("HTTP 503"));
        assertThat(generator.normalize("SQLState 23000"))
                .isNotEqualTo(generator.normalize("SQLState 40001"));
    }

    @Test
    void sampleKeyIsIndependentFromGroupCategory() {
        ErrorContentNormalizer generator = new ErrorContentNormalizer();
        ExceptionStructure structure = structure(204);

        assertThat(generator.sampleKey(structure, "same message"))
                .isEqualTo(generator.sampleKey(structure, "same message"));
    }

    @Test
    void sampleKeyIgnoresBusinessLine() {
        ErrorContentNormalizer generator = new ErrorContentNormalizer();

        assertThat(generator.sampleKey(structure(204), "same message"))
                .isEqualTo(generator.sampleKey(structure(211), "same message"));
    }

    @Test
    void headerMessageIsUsedWithoutExceptionAndIgnoresHeaderMetadata() {
        AnalyzedError first = processor().process(headerOnlyEvent(
                "host-a", "2026-08-18 10:00:00,001", "thread-a", "tid-a", "库存同步失败"));
        AnalyzedError second = processor().process(headerOnlyEvent(
                "host-b", "2026-08-19 11:12:13,002", "thread-b", "tid-b", "库存同步失败"));

        assertThat(first.getStructure().getExceptionClass()).isNull();
        assertThat(first.getStructure().getBusinessClass()).isNull();
        assertThat(first.getNormalizedMessage()).isEqualTo("库存同步失败");
        assertThat(first.getStableFingerprint()).isEqualTo(second.getStableFingerprint());
    }

    @Test
    void headerlessBodyAndEmptyEventUseNonBlankFallback() {
        AnalyzedError body = processor().process(headerlessEvent("unstructured error body"));
        AnalyzedError empty = processor().process(headerlessEvent("   "));

        assertThat(body.getNormalizedMessage()).isEqualTo("unstructured error body");
        assertThat(empty.getNormalizedMessage()).isEqualTo("{empty-event}");
        assertThat(body.getStableFingerprint()).isNotEqualTo(empty.getStableFingerprint());
    }

    @Test
    void rabbitTriggerNeverOverridesCodeRootCause() {
        AnalyzedError result = processor().process(event(10, "order 123 failed"));

        assertThat(result.getCategory()).isEqualTo(RootCauseCategory.CODE);
        assertThat(result.getTriggerChannel()).isEqualTo(TriggerChannel.MQ);
    }

    @Test
    void masksJsonNumbersButKeepsSemanticCodes() {
        ErrorContentNormalizer generator = new ErrorContentNormalizer();

        assertThat(generator.normalize("出库单费用结算失败 {\"belongCompany\":192,\"inDetailId\":39335}"))
                .isEqualTo(generator.normalize("出库单费用结算失败 {\"belongCompany\":201,\"inDetailId\":34931}"));
        assertThat(generator.normalize("{\"status\":500,\"belongCompany\":192}"))
                .isNotEqualTo(generator.normalize("{\"status\":503,\"belongCompany\":192}"));
        assertThat(generator.normalize("{\"errorCode\":1001}"))
                .isNotEqualTo(generator.normalize("{\"errorCode\":1002}"));
    }

    @Test
    void deadQueueJsonUsesTemplateWithoutChangingRawEvent() {
        String firstMessage = "DeadQueue消费者收到消息: "
                + "{\"id\":100001,\"buyerName\":\"Sample Buyer A\",\"actualTotalPrice\":979.0}";
        String secondMessage = "DeadQueue消费者收到消息: "
                + "{\"id\":100002,\"buyerName\":\"Sample Buyer B\",\"actualTotalPrice\":4398.0}";

        AnalyzedError first = processor().process(headerOnlyEvent(
                "host-a", "2026-08-18 10:00:00,001", "thread-a", "tid-a", firstMessage));
        AnalyzedError second = processor().process(headerOnlyEvent(
                "host-a", "2026-08-18 10:00:01,001", "thread-a", "tid-b", secondMessage));

        assertThat(first.getNormalizedMessage()).isEqualTo(MessageTemplater.DEAD_QUEUE_TEMPLATE);
        assertThat(first.getStableFingerprint()).isEqualTo(second.getStableFingerprint());
        assertThat(first.getEvent().getMessage()).isEqualTo(firstMessage);
        assertThat(first.getEvent().getContent()).contains(firstMessage);
    }

    @Test
    void deadQueueRuleDoesNotTemplateTruncatedOrUnrelatedMessages() {
        MessageTemplater templater = new MessageTemplater();
        String truncated = "DeadQueue消费者收到消息: {\"id\":100001";
        String unrelated = "请求失败: {\"id\":100001,\"status\":500}";

        assertThat(templater.template(truncated)).isEqualTo(truncated);
        assertThat(templater.template(unrelated)).isEqualTo(unrelated);
    }

    @Test
    void expectedClassificationDoesNotParticipateInStableFingerprint() {
        RootCauseRule suppressRule = new RootCauseRule(
                "default",
                RootCauseCategory.BUSINESS,
                RootCauseRuleType.KEYWORD,
                "expected marker",
                10,
                true,
                "EXPECTED_MARKER",
                null);
        ErrorEventProcessor processor = processor(new RootCauseClassifier(
                Collections.singletonList(suppressRule)));

        AnalyzedError expected = processor.process(exceptionEvent(
                "expected marker while processing order", "same root cause"));
        AnalyzedError actionable = processor.process(exceptionEvent(
                "unexpected failure while processing order", "same root cause"));

        assertThat(expected.isExpected()).isTrue();
        assertThat(expected.getReasonCode()).isEqualTo("EXPECTED_MARKER");
        assertThat(actionable.isExpected()).isFalse();
        assertThat(expected.getStableFingerprint()).isEqualTo(actionable.getStableFingerprint());
    }

    @Test
    void explicitRulesOnlyDriveClassificationAndFallback() {
        RootCauseClassifier classifier = DEFAULT_CLASSIFIER;

        assertThat(classifier.classify(exceptionStructure(
                "org.hibernate.exception.LockAcquisitionException", "boom")))
                .isEqualTo(RootCauseCategory.DATABASE);
        assertThat(classifier.classify(exceptionStructure(
                "java.net.NoRouteToHostException", "boom")))
                .isEqualTo(RootCauseCategory.EXTERNAL_SERVICE);
        assertThat(classifier.classify(exceptionStructure(
                "io.lettuce.core.ProtocolViolationException", "boom")))
                .isEqualTo(RootCauseCategory.INFRASTRUCTURE);
        assertThat(classifier.classify(exceptionStructure(
                "com.badfisher.lk.wms.exception.StockException", "boom")))
                .isEqualTo(RootCauseCategory.BUSINESS);
        assertThat(classifier.classify(exceptionStructure("java.lang.RuntimeException", "boom")))
                .isEqualTo(RootCauseCategory.UNKNOWN);
    }

    private static ErrorEventProcessor processor() {
        return processor(DEFAULT_CLASSIFIER);
    }

    private static ErrorEventProcessor processor(RootCauseClassifier classifier) {
        return new ErrorEventProcessor(
                new ExceptionStructureExtractor("com.badfisher", 15),
                new StackSimplifier(16384),
                classifier,
                new ErrorContentNormalizer());
    }

    private static LogEvent exceptionEvent(String headerMessage, String rootCauseMessage) {
        String content = "host-a || 2026-08-18 10:00:00,001 [worker-1][TID:tid-1]"
                + " ERROR com.badfisher.OrderService.create(10) - " + headerMessage
                + "\njava.lang.RuntimeException: " + rootCauseMessage
                + "\n\tat com.badfisher.OrderService.create(OrderService.java:10)";
        return new LogEvent(Instant.parse("2026-08-18T02:00:00Z"), "ERROR", "worker-1", null,
                "tid-1", null, "com.badfisher.OrderService", "create", Integer.valueOf(10),
                headerMessage, content, 1, 3, 0, content.length(),
                LogLocationMode.PLAIN_BYTE_OFFSET, false);
    }

    private static LogEvent event(int line, String message) {
        String content = "2026-08-18 10:00:00.001 ERROR [mq-1] RabbitListener failed"
                + "\njava.lang.NullPointerException: " + message
                + "\n\tat com.badfisher.OrderService.create(OrderService.java:" + line + ")"
                + "\n\tat org.springframework.cglib.Proxy.invoke(Proxy.java:2)";
        return new LogEvent(
                Instant.parse("2026-08-18T02:00:00Z"),
                "ERROR",
                "org.springframework.amqp.rabbit.RabbitListenerEndpointContainer#1-1",
                null,
                "tid-1",
                null,
                "com.badfisher.OrderReceiver",
                "process",
                Integer.valueOf(68),
                content,
                1,
                4,
                0,
                200,
                LogLocationMode.PLAIN_BYTE_OFFSET,
                false);
    }

    private static LogEvent headerOnlyEvent(String host, String timestamp, String thread,
            String tid, String message) {
        String content = host + " || " + timestamp + " [" + thread + "][TID:" + tid
                + "] ERROR com.badfisher.OrderService.create(10) - " + message;
        return new LogEvent(Instant.parse("2026-08-18T02:00:00Z"), "ERROR", thread, null,
                tid, null, "com.badfisher.OrderService", "create", Integer.valueOf(10), message,
                content, 1, 1, 0, content.length(), LogLocationMode.PLAIN_BYTE_OFFSET, false);
    }

    private static LogEvent headerlessEvent(String content) {
        return new LogEvent(null, "ERROR", null, null, null, null, content, 1, 1, 0,
                content.length(), LogLocationMode.PLAIN_BYTE_OFFSET, false);
    }

    private static ExceptionStructure exceptionStructure(String exceptionClass, String message) {
        return new ExceptionStructure(
                exceptionClass, message, exceptionClass, message,
                null, null, null, null, null, null,
                Collections.<String>emptyList());
    }

    private static ExceptionStructure structure(int line) {
        return new ExceptionStructure(
                "java.lang.RuntimeException", "same message", "java.lang.IllegalStateException",
                "same message", "com.badfisher.OrderService", "create", Integer.valueOf(line),
                "com.badfisher.OrderService", "create", Integer.valueOf(line),
                Collections.singletonList("at com.badfisher.OrderService.create(OrderService.java:"
                        + line + ")"));
    }
}
