package io.github.badfisher.ailog.analysis.aggregation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.analysis.issue.ErrorContentNormalizer;
import io.github.badfisher.ailog.domain.aggregation.AggregateBatch;
import io.github.badfisher.ailog.domain.aggregation.AggregateType;
import io.github.badfisher.ailog.domain.aggregation.AggregatedError;
import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository.ErrorEntry;
import io.github.badfisher.ailog.domain.issue.AnalyzedError;
import io.github.badfisher.ailog.domain.issue.ExceptionStructure;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.issue.TriggerChannel;
import io.github.badfisher.ailog.domain.log.LogEvent;
import io.github.badfisher.ailog.domain.log.LogLocationMode;

/** 文件级流式聚合的身份、单样本、计数守恒和真实驻留内存测试。 */
class StreamingErrorAggregatorTest {

    @Test
    void shouldAggregateTenThousandExpectedBusinessErrorsWithoutGrowingResidentBytes() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 20000L, 16384);
        assertNull(aggregator.add(businessEntry(1, true, "ORDER_STATUS", "order-1")));
        long residentBytes = aggregator.estimatedResidentBytes();

        for (int index = 2; index <= 10000; index++) {
            assertNull(aggregator.add(businessEntry(index, true, "ORDER_STATUS",
                    "order-" + index)));
        }

        assertEquals(1, aggregator.bucketCount());
        assertEquals(residentBytes, aggregator.estimatedResidentBytes());
        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(10000L, result.getOccurrenceCount());
        assertEquals(Instant.parse("2026-08-25T02:00:01Z"), result.getFirstSeenTime());
        assertEquals(Instant.parse("2026-08-25T04:46:40Z"), result.getLastSeenTime());
        assertNotNull(result.getSample());
    }

    @Test
    void shouldAggregateTenThousandCodeErrorsIntoOneBucketAndOneSample() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 20000L, 100);
        for (int index = 1; index <= 10000; index++) {
            assertNull(aggregator.add(issueEntry(index, "same-stable", "same error", "stack")));
        }

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(AggregateType.ISSUE, result.getAggregateType());
        assertEquals(10000L, result.getOccurrenceCount());
        assertNotNull(result.getSample());
    }

    @Test
    void shouldIgnoreLegacyMessageFingerprintForCodeIdentity() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(issueEntry(1, "stable-1", "same root cause", "same stack"));
        aggregator.add(issueEntry(2, "stable-2", "same root cause", "same stack"));

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(2L, result.getOccurrenceCount());
    }

    @Test
    void shouldAggregateExternalDynamicCustomerCodesIntoOneIssue() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(externalDuplicateCustomerEntry(1L, "CUST0001"));
        aggregator.add(externalDuplicateCustomerEntry(2L, "ABC001"));
        aggregator.add(externalDuplicateCustomerEntry(3L, "US-9988"));

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(3L, result.getOccurrenceCount());
    }

    @Test
    void shouldAggregateUnknownMessagesByLoggerIdentity() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(unknownEntry(1L, "com.badfisher.OrderService", "syncOrder",
                null, null, "channel 667"));
        aggregator.add(unknownEntry(2L, "com.badfisher.OrderService", "syncOrder",
                null, null, "channel 673"));
        aggregator.add(unknownEntry(3L, "com.badfisher.OrderService", "syncOrder",
                null, null, "channel 675"));

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(3L, result.getOccurrenceCount());
    }

    @Test
    void shouldUseOutermostExceptionClassForUnknownIdentity() {
        StreamingErrorAggregator repeated = aggregator(10, 1024L * 1024L, 100L, 100);
        repeated.add(unknownEntry(1L, "com.badfisher.OrderService", "syncOrder",
                "java.lang.NullPointerException", "java.lang.RuntimeException", "first"));
        repeated.add(unknownEntry(2L, "com.badfisher.OrderService", "syncOrder",
                "java.lang.NullPointerException", "java.lang.RuntimeException", "second"));
        assertEquals(2L, onlyError(repeated.drain()).getOccurrenceCount());

        StreamingErrorAggregator different = aggregator(10, 1024L * 1024L, 100L, 100);
        different.add(unknownEntry(1L, "com.badfisher.OrderService", "syncOrder",
                "java.lang.NullPointerException", "java.lang.RuntimeException", "first"));
        different.add(unknownEntry(2L, "com.badfisher.OrderService", "syncOrder",
                "java.lang.IllegalArgumentException", "java.lang.RuntimeException", "second"));

        AggregateBatch batch = different.drain();
        assertEquals(2, batch.getErrors().size());
        assertNotEquals(batch.getErrors().get(0).getStableFingerprint(),
                batch.getErrors().get(1).getStableFingerprint());
    }

    @Test
    void shouldAggregateDatabaseIssuesByFamilyIdentity() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(entry(1L, RootCauseCategory.DATABASE, false, null,
                "legacy-97105", "lastPacketReceivedIdleMillis : 97105",
                "java.sql.SQLException", "stack"));
        aggregator.add(entry(2L, RootCauseCategory.DATABASE, false, null,
                "legacy-120387", "lastPacketReceivedIdleMillis : 120387",
                "java.sql.SQLException", "stack"));
        aggregator.add(entry(3L, RootCauseCategory.DATABASE, false, null,
                "legacy-89788", "lastPacketReceivedIdleMillis : 89788",
                "java.sql.SQLException", "stack"));

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(3L, result.getOccurrenceCount());
    }

    @Test
    void shouldAggregateDatabaseMessagesWhenRuleReasonIsTheSame() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(entry(1L, RootCauseCategory.DATABASE, false, null,
                "legacy-23000", "SQLState 23000", "java.sql.SQLException", "stack"));
        aggregator.add(entry(2L, RootCauseCategory.DATABASE, false, null,
                "legacy-40001", "SQLState 40001", "java.sql.SQLException", "stack"));

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(2L, result.getOccurrenceCount());
    }

    @Test
    void shouldKeepDifferentBusinessSemanticCodesSeparate() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(businessEntry(1L, false, "WAREHOUSE", "sku-100 unavailable"));
        aggregator.add(businessEntry(2L, false, "ORDER", "order-200 unavailable"));

        assertEquals(2, aggregator.drain().getErrors().size());
    }

    @Test
    void shouldAggregateExternalDynamicMessagesButKeepOperationsAndStatusesSeparate() {
        StreamingErrorAggregator repeated = aggregator(10, 1024L * 1024L, 100L, 100);
        repeated.add(structuralEntry(1L, RootCauseCategory.EXTERNAL_SERVICE,
                "request order-100 failed", "java.lang.RuntimeException",
                "java.net.SocketTimeoutException", "com.badfisher.OdooClient", "getOrder", 24L));
        repeated.add(structuralEntry(2L, RootCauseCategory.EXTERNAL_SERVICE,
                "request order-200 failed", "java.lang.RuntimeException",
                "java.net.SocketTimeoutException", "com.badfisher.OdooClient", "getOrder", 24L));
        assertEquals(2L, onlyError(repeated.drain()).getOccurrenceCount());

        StreamingErrorAggregator differentOperations = aggregator(
                10, 1024L * 1024L, 100L, 100);
        differentOperations.add(structuralEntry(1L, RootCauseCategory.EXTERNAL_SERVICE,
                "request failed", "java.lang.RuntimeException",
                "java.net.SocketTimeoutException", "com.badfisher.OdooClient", "getOrder", 24L));
        differentOperations.add(structuralEntry(2L, RootCauseCategory.EXTERNAL_SERVICE,
                "request failed", "java.lang.RuntimeException",
                "java.net.SocketTimeoutException", "com.badfisher.OdooClient", "getProduct", 24L));
        assertEquals(2, differentOperations.drain().getErrors().size());

        StreamingErrorAggregator differentStatuses = aggregator(
                10, 1024L * 1024L, 100L, 100);
        differentStatuses.add(structuralEntry(1L, RootCauseCategory.EXTERNAL_SERVICE,
                "upstream HTTP 500", "java.lang.RuntimeException",
                "com.badfisher.FeignResponseException", "com.badfisher.OdooClient", "getOrder", 26L));
        differentStatuses.add(structuralEntry(2L, RootCauseCategory.EXTERNAL_SERVICE,
                "upstream HTTP 503", "java.lang.RuntimeException",
                "com.badfisher.FeignResponseException", "com.badfisher.OdooClient", "getOrder", 26L));
        assertEquals(2L, onlyError(differentStatuses.drain()).getOccurrenceCount());
    }

    @Test
    void shouldAggregateByStructureAndKeepDifferentExceptionsSeparate() {
        StreamingErrorAggregator repeated = aggregator(10, 1024L * 1024L, 100L, 100);
        repeated.add(structuralEntry(1L, RootCauseCategory.CODE,
                "order-100 failed", "java.lang.RuntimeException",
                "java.lang.NullPointerException", "com.badfisher.OrderService", "save", 32L));
        repeated.add(structuralEntry(2L, RootCauseCategory.CODE,
                "order-200 failed", "java.lang.RuntimeException",
                "java.lang.NullPointerException", "com.badfisher.OrderService", "save", 32L));
        assertEquals(2L, onlyError(repeated.drain()).getOccurrenceCount());

        StreamingErrorAggregator different = aggregator(10, 1024L * 1024L, 100L, 100);
        different.add(structuralEntry(1L, RootCauseCategory.CODE,
                "first", "java.lang.RuntimeException", "java.lang.NullPointerException",
                "com.badfisher.OrderService", "save", 32L));
        different.add(structuralEntry(2L, RootCauseCategory.CODE,
                "second", "java.lang.RuntimeException", "java.lang.IllegalArgumentException",
                "com.badfisher.OrderService", "save", 32L));
        assertEquals(2, different.drain().getErrors().size());
    }

    @Test
    void shouldAggregateInfrastructureBySemanticStructureAndKeepRootCausesSeparate() {
        StreamingErrorAggregator repeated = aggregator(10, 1024L * 1024L, 100L, 100);
        repeated.add(structuralEntry(1L, RootCauseCategory.INFRASTRUCTURE,
                "redis key-100 failed", "java.lang.RuntimeException",
                "io.lettuce.core.RedisException", "com.badfisher.CacheService", "get", 30L));
        repeated.add(structuralEntry(2L, RootCauseCategory.INFRASTRUCTURE,
                "redis key-200 failed", "java.lang.RuntimeException",
                "io.lettuce.core.RedisException", "com.badfisher.CacheService", "get", 30L));
        assertEquals(2L, onlyError(repeated.drain()).getOccurrenceCount());

        StreamingErrorAggregator different = aggregator(10, 1024L * 1024L, 100L, 100);
        different.add(structuralEntry(1L, RootCauseCategory.INFRASTRUCTURE,
                "redis failed", "java.lang.RuntimeException",
                "io.lettuce.core.RedisException", "com.badfisher.CacheService", "get", 30L));
        different.add(structuralEntry(2L, RootCauseCategory.INFRASTRUCTURE,
                "memory failed", "java.lang.RuntimeException",
                "java.lang.OutOfMemoryError", "com.badfisher.CacheService", "get", 28L));
        assertEquals(2, different.drain().getErrors().size());
    }

    @Test
    void shouldRejectUnknownWithoutLoggerOrExceptionIdentity() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);

        assertThrows(IllegalArgumentException.class,
                () -> aggregator.add(unknownEntry(1L, null, null, null, null, "unknown")));
    }

    @Test
    void shouldDrainAtBucketAndResidentByteLimits() {
        StreamingErrorAggregator bucketLimited = aggregator(2, 1024L * 1024L, 100L, 100);
        assertNull(bucketLimited.add(entry(1L, RootCauseCategory.CODE, false, null,
                "stable-1", "one", "java.lang.NullPointerException", "stack")));
        AggregateBatch bucketBatch = bucketLimited.add(
                entry(2L, RootCauseCategory.CODE, false, null,
                        "stable-2", "two", "java.lang.IllegalArgumentException", "stack"));
        assertNotNull(bucketBatch);
        assertEquals(2, bucketBatch.getErrors().size());

        StreamingErrorAggregator byteLimited = aggregator(100, 1L, 100L, 100);
        AggregateBatch byteBatch = byteLimited.add(
                issueEntry(1, "stable-1", "large", repeat('s', 100)));
        assertNotNull(byteBatch);
        assertEquals(1L, byteBatch.getOccurrenceCount());
    }

    @Test
    void shouldFlushByAcceptedErrorCountAndClearAllState() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100000L, 100);
        AggregateBatch batch = null;
        for (int index = 1; index <= 100000; index++) {
            batch = aggregator.add(issueEntry(index, "same-stable", "same", "stack"));
        }

        assertNotNull(batch);
        assertEquals(100000L, batch.getOccurrenceCount());
        assertTrue(aggregator.isEmpty());
        assertEquals(0, aggregator.bucketCount());
        assertEquals(0L, aggregator.estimatedResidentBytes());
        assertEquals(0L, aggregator.acceptedErrorsSinceFlush());
    }

    @Test
    void shouldKeepEventRowsFarBelowAcceptedErrorsForLargeLowCardinalityInput() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 300000L, 100);
        ErrorEntry repeated = businessEntry(1, true, "HTTP_FORBIDDEN", "same representative event");
        for (int index = 0; index < 212863; index++) {
            assertNull(aggregator.add(repeated));
        }

        AggregateBatch batch = aggregator.drain();
        assertEquals(1, batch.getErrors().size());
        assertEquals(212863L, batch.getOccurrenceCount());
    }

    @Test
    void shouldTruncateSampleAndKeepExactlyOneRepresentative() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 32);
        aggregator.add(issueEntry(1, "same-stable", repeat('x', 80), "stack"));
        aggregator.add(issueEntry(2, "same-stable", "second", "stack"));

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(2L, result.getOccurrenceCount());
        assertEquals(32, result.getSample().getSampleContent().length());
        assertTrue(result.getSample().isSampleContentTruncated());
        assertEquals(1L, result.getSample().getStartLine());
    }

    @Test
    void shouldDistinguishSourceEventTruncationFromSampleContentTruncation() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 65536);
        aggregator.add(entry(1, RootCauseCategory.CODE, false, null,
                "same-stable", "short content", "java.lang.IllegalStateException", "stack", true));

        AggregatedError result = onlyError(aggregator.drain());
        assertTrue(result.getSample().isSourceEventTruncated());
        assertFalse(result.getSample().isSampleContentTruncated());
    }

    @Test
    void shouldKeepCompleteSampleAtExactLimitAndTruncateOnlyWhenExceeded() {
        StreamingErrorAggregator exactLimit = aggregator(10, 1024L * 1024L, 100L, 65536);
        exactLimit.add(issueEntry(1, "exact", repeat('x', 65536), "stack"));
        assertEquals(65536, onlyError(exactLimit.drain()).getSample().getSampleContent().length());

        StreamingErrorAggregator exceededLimit = aggregator(10, 1024L * 1024L, 100L, 65536);
        exceededLimit.add(issueEntry(1, "exceeded", repeat('x', 65537), "stack"));
        assertTrue(onlyError(exceededLimit.drain()).getSample().isSampleContentTruncated());
    }

    @Test
    void shouldStopTryingToImproveSampleAfterConfiguredAttempts() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100, 2);
        aggregator.add(entry(1, RootCauseCategory.CODE, false, null,
                "same-stable", "first", null, null));
        aggregator.add(entry(2, RootCauseCategory.CODE, false, null,
                "same-stable", "second", null, null));
        aggregator.add(entry(3, RootCauseCategory.CODE, false, null,
                "same-stable", "complete", null, "complete stack"));

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(1L, result.getSample().getStartLine());
        assertEquals(3L, result.getOccurrenceCount());
    }

    @Test
    void shouldKeepFirstCodeSampleWhenLaterEvidenceHasSameIdentityQuality() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(entry(1, RootCauseCategory.CODE, false, null,
                "same-stable", "message", "java.lang.NullPointerException", null));
        aggregator.add(entry(2, RootCauseCategory.CODE, false, null,
                "same-stable", "message", "java.lang.NullPointerException", "complete stack"));
        aggregator.add(entry(3, RootCauseCategory.CODE, false, null,
                "same-stable", "later message", "java.lang.NullPointerException", "later stack"));

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(1L, result.getSample().getStartLine());
        assertNull(result.getSample().getSimplifiedStack());
        assertEquals(3L, result.getOccurrenceCount());
    }

    @Test
    void shouldHashBusinessIdentityAndRejectMissingReasonCode() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(businessEntry(1, true, "TIKTOK_FULL_MANAGED_ORDER_INCOMPLETE", "order-1"));
        aggregator.add(businessEntry(2, true, "TIKTOK_FULL_MANAGED_ORDER_INCOMPLETE", "order-2"));

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(64, result.getAggregateKey().length());
        assertEquals(2L, result.getOccurrenceCount());
        assertEquals("TIKTOK_FULL_MANAGED_ORDER_INCOMPLETE", result.getReasonCode());

        StreamingErrorAggregator invalid = aggregator(10, 1024L, 100L, 100);
        assertThrows(IllegalArgumentException.class,
                () -> invalid.add(businessEntry(1, true, null, "invalid")));
    }

    @Test
    void shouldUseIssueIdentityForUnexpectedBusinessError() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(businessEntry(1, false, "REASON", "content"));

        assertEquals(AggregateType.ISSUE, onlyError(aggregator.drain()).getAggregateType());
    }

    @Test
    void shouldFreezeExpectedBusinessWithMessageAndContentOnly() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(entry(1, RootCauseCategory.BUSINESS, true,
                "EXPECTED_REASON", "business-stable",
                "clear business message", null, null));
        aggregator.add(entry(2, RootCauseCategory.BUSINESS, true,
                "EXPECTED_REASON", "business-stable",
                "later technical evidence", "java.lang.IllegalStateException", "later stack"));

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(1L, result.getSample().getStartLine());
        assertEquals(2L, result.getOccurrenceCount());
    }

    @Test
    void shouldKeepExpectedBusinessIndependentOfIssueFingerprint() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(entry(1, RootCauseCategory.BUSINESS, true,
                "EXPECTED_REASON", null,
                "business message", null, null));

        assertEquals(AggregateType.BUSINESS, onlyError(aggregator.drain()).getAggregateType());
    }

    @Test
    void shouldImproveUnexpectedBusinessUntilExceptionEvidenceIsAvailable() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(entry(1, RootCauseCategory.BUSINESS, false,
                "UNEXPECTED_REASON", "business-stable",
                "business message only", null, null));
        aggregator.add(entry(2, RootCauseCategory.BUSINESS, false,
                "UNEXPECTED_REASON", "business-stable",
                "business message with failure", "java.lang.IllegalStateException",
                "technical stack"));

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(2L, result.getSample().getStartLine());
        assertEquals("technical stack", result.getSample().getSimplifiedStack());
    }

    /**
     * 回归：matchedRuleId 必须跟随胜出的代表样本。
     *
     * <p>同一 Bucket（非预期 BUSINESS 身份不含根因异常，根因/栈可变不影响聚合）：
     * 低质量样本（规则10）→ 高质量样本（规则20）→ 更差样本（规则30），
     * 最终代表样本为规则 20 的输入，matchedRuleId 不得停留在首个决策或随最后一次输入漂移。</p>
     */
    @Test
    void keepsMatchedRuleIdAlignedWithWinningRepresentativeSample() {
        StreamingErrorAggregator aggregator = aggregator(10, 1024L * 1024L, 100L, 100);
        aggregator.add(entry(1, RootCauseCategory.BUSINESS, false, null, "business-stable",
                "poor evidence", null, null, Long.valueOf(10L)));
        aggregator.add(entry(2, RootCauseCategory.BUSINESS, false, null, "business-stable",
                "complete evidence", "java.lang.IllegalStateException",
                "quality stack", Long.valueOf(20L)));
        aggregator.add(entry(3, RootCauseCategory.BUSINESS, false, null, "business-stable",
                "late worse evidence", null, null, Long.valueOf(30L)));

        AggregatedError result = onlyError(aggregator.drain());
        assertEquals(3L, result.getOccurrenceCount());
        assertEquals("complete evidence", result.getSample().getSampleContent());
        assertEquals("quality stack", result.getSample().getSimplifiedStack());
        assertEquals(Long.valueOf(20L), result.getSample().getMatchedRuleId());
        assertEquals(Long.valueOf(20L), result.getMatchedRuleId());
    }

    private StreamingErrorAggregator aggregator(int maxBuckets, long maxBytes,
            long flushAfterAcceptedErrors, int maxContent) {
        return aggregator(maxBuckets, maxBytes, flushAfterAcceptedErrors, maxContent, 5);
    }

    private StreamingErrorAggregator aggregator(int maxBuckets, long maxBytes,
            long flushAfterAcceptedErrors, int maxContent, int maxSampleImproveAttempts) {
        return new StreamingErrorAggregator("dev", "badfisher", "sample-service",
                maxBuckets, maxBytes, flushAfterAcceptedErrors, maxContent,
                maxSampleImproveAttempts);
    }

    private AggregatedError onlyError(AggregateBatch batch) {
        assertEquals(1, batch.getErrors().size());
        return batch.getErrors().get(0);
    }

    private ErrorEntry businessEntry(long line, boolean expected, String reasonCode, String content) {
        return entry(line, RootCauseCategory.BUSINESS, expected, reasonCode,
                "business-stable", content, "java.lang.BusinessException", "business stack");
    }

    private ErrorEntry issueEntry(long line, String stableFingerprint, String content,
            String stack) {
        return entry(line, RootCauseCategory.CODE, false, null, stableFingerprint,
                content, "java.lang.IllegalStateException", stack);
    }

    private ErrorEntry externalDuplicateCustomerEntry(long line, String customerCode) {
        String message = "推送外部服务失败，原因java.lang.RuntimeException: "
                + "客户编码为“" + customerCode + "”的客户，检测到有相同名称的客户已存在";
        return entry(line, RootCauseCategory.EXTERNAL_SERVICE, false, null,
                "sample-system-stable-" + customerCode, message,
                "com.badfisher.common.core.exception.FeignResponseException", "sample-system stack");
    }

    private ErrorEntry unknownEntry(long line, String loggerClass, String loggerMethod,
            String exceptionClass, String rootCauseException, String content) {
        Instant timestamp = Instant.parse("2026-08-25T02:00:00Z").plusSeconds(line);
        LogEvent event = new LogEvent(timestamp, "ERROR", "main", null, null, null,
                loggerClass, loggerMethod, Integer.valueOf(10), content, content,
                line, line, line * 100L, line * 100L + content.length(),
                LogLocationMode.PLAIN_BYTE_OFFSET, false);
        ExceptionStructure structure = new ExceptionStructure(
                exceptionClass, content, rootCauseException, content,
                loggerClass, loggerMethod, Integer.valueOf(10), null, null, null,
                Collections.<String>emptyList());
        AnalyzedError error = new AnalyzedError(event, structure, RootCauseCategory.UNKNOWN,
                TriggerChannel.UNKNOWN, null, content, "strict-" + line,
                "legacy-unknown-" + line, ErrorContentNormalizer.GROUPING_MARKER,
                null, false, null);
        return new ErrorEntry(error, "STRICT_ERROR");
    }

    private ErrorEntry structuralEntry(long line, RootCauseCategory category, String content,
            String exceptionClass, String rootCauseException, String ownerClass,
            String ownerMethod, Long matchedRuleId) {
        Instant timestamp = Instant.parse("2026-08-25T02:00:00Z").plusSeconds(line);
        LogEvent event = new LogEvent(timestamp, "ERROR", "main", null, null, null,
                ownerClass, ownerMethod, Integer.valueOf(10), content, content,
                line, line, line * 100L, line * 100L + content.length(),
                LogLocationMode.PLAIN_BYTE_OFFSET, false);
        ExceptionStructure structure = new ExceptionStructure(
                exceptionClass, content, rootCauseException, content,
                ownerClass, ownerMethod, Integer.valueOf(10), ownerClass, ownerMethod,
                Integer.valueOf(10), Collections.singletonList(
                        "at " + ownerClass + "." + ownerMethod + "(Source.java:10)"));
        AnalyzedError error = new AnalyzedError(event, structure, category,
                TriggerChannel.HTTP, "stack", content, "strict-" + line,
                "legacy-stable-" + line, ErrorContentNormalizer.GROUPING_MARKER,
                matchedRuleId, false, null);
        return new ErrorEntry(error, "STRICT_ERROR");
    }

    private ErrorEntry entry(long line, RootCauseCategory category, boolean expected,
            String reasonCode, String stableFingerprint, String content,
            String rootCauseException, String stack) {
        return entry(line, category, expected, reasonCode, stableFingerprint,
                content, rootCauseException, stack, false);
    }

    private ErrorEntry entry(long line, RootCauseCategory category, boolean expected,
            String reasonCode, String stableFingerprint, String content,
            String rootCauseException, String stack, boolean sourceEventTruncated) {
        return entry(line, category, expected, reasonCode, stableFingerprint,
                content, rootCauseException, stack, sourceEventTruncated, Long.valueOf(1L));
    }

    private ErrorEntry entry(long line, RootCauseCategory category, boolean expected,
            String reasonCode, String stableFingerprint, String content,
            String rootCauseException, String stack, Long matchedRuleId) {
        return entry(line, category, expected, reasonCode, stableFingerprint,
                content, rootCauseException, stack, false, matchedRuleId);
    }

    private ErrorEntry entry(long line, RootCauseCategory category, boolean expected,
            String reasonCode, String stableFingerprint, String content,
            String rootCauseException, String stack, boolean sourceEventTruncated,
            Long matchedRuleId) {
        Instant timestamp = Instant.parse("2026-08-25T02:00:00Z").plusSeconds(line);
        LogEvent event = new LogEvent(timestamp, "ERROR", "main", null, null, null,
                content, line, line, line * 100L, line * 100L + content.length(),
                LogLocationMode.PLAIN_BYTE_OFFSET, sourceEventTruncated);
        ExceptionStructure structure = new ExceptionStructure(
                "java.lang.IllegalStateException", content, rootCauseException, content,
                "com.badfisher.OrderService", "validate", 100, "com.badfisher.OrderService",
                "validate", 100,
                Collections.singletonList("at com.badfisher.OrderService.validate(OrderService.java:100)"));
        AnalyzedError error = new AnalyzedError(event, structure, category,
                TriggerChannel.HTTP, stack, content, "strict-" + line, stableFingerprint,
                ErrorContentNormalizer.GROUPING_MARKER, matchedRuleId, expected, reasonCode);
        return new ErrorEntry(error, "STRICT_ERROR");
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
