package io.github.badfisher.ailog.analysis.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.analysis.module.ErrorStatisticsCollector;
import io.github.badfisher.ailog.domain.log.LogReference;
import io.github.badfisher.ailog.domain.log.RawLogEntry;

class ErrorLogStatisticsServiceTest {

    private final ErrorLogStatisticsService service =
            new ErrorLogStatisticsService(new ErrorLogClassifier());

    @Test
    void classifiesAndAggregatesErrorLogs() {
        ErrorStatistics result = service.summarize(Arrays.asList(
                entry("ERROR com.badfisher.lk.sms.mq.OrderReceiver.process(10) - 库存不足，自动配货失败"),
                entry("ERROR com.badfisher.lk.sms.mq.OrderReceiver.process(10) - consume failed"),
                entry("ERROR com.badfisher.lk.sms.service.EbayOauthApiService.crawlerByToken(20) - Forbidden"),
                entry("ERROR com.badfisher.lk.order.OrderService.save(30) - java.lang.NullPointerException: value")));

        assertThat(result.getTotalCount()).isEqualTo(4L);
        assertThat(result.getParsedSourceCount()).isEqualTo(4L);
        assertThat(result.getJavaExceptionCount()).isEqualTo(1L);
        assertThat(result.getCategoryCounts())
                .containsEntry("库存不足/自动配货失败", 1L)
                .containsEntry("MQ消费处理失败", 1L)
                .containsEntry("外部平台鉴权失败", 1L)
                .containsEntry("Java异常", 1L);
        assertThat(result.getSourceMethodCounts())
                .containsEntry("com.badfisher.lk.sms.mq.OrderReceiver.process", 2L);
        assertThat(result.getExceptionClassCounts())
                .containsEntry("java.lang.NullPointerException", 1L);
    }

    @Test
    void returnsImmutableStatisticsMaps() {
        ErrorStatistics result = service.summarize(Collections.singletonList(
                entry("ERROR com.badfisher.Test.run(1) - failed")));

        assertThrows(UnsupportedOperationException.class,
                () -> result.getCategoryCounts().put("invalid", 1L));
    }

    @Test
    void collectorMatchesBatchSummarize() {
        List<RawLogEntry> entries = Arrays.asList(
                entry("ERROR com.badfisher.lk.sms.mq.OrderReceiver.process(10) - 库存不足，自动配货失败"),
                entry("ERROR com.badfisher.lk.sms.mq.OrderReceiver.process(10) - consume failed"),
                entry("ERROR com.badfisher.lk.order.OrderService.save(30) - java.lang.NullPointerException: value"));

        ErrorStatisticsCollector collector = service.newCollector();
        for (RawLogEntry entry : entries) {
            collector.accept(entry);
        }
        ErrorStatistics streamed = collector.build();
        ErrorStatistics batched = service.summarize(entries);

        assertThat(streamed.getTotalCount()).isEqualTo(batched.getTotalCount());
        assertThat(streamed.getJavaExceptionCount()).isEqualTo(batched.getJavaExceptionCount());
        assertThat(streamed.getParsedSourceCount()).isEqualTo(batched.getParsedSourceCount());
        assertThat(streamed.getCategoryCounts()).isEqualTo(batched.getCategoryCounts());
        assertThat(streamed.getSourceClassCounts()).isEqualTo(batched.getSourceClassCounts());
        assertThat(streamed.getSourceMethodCounts()).isEqualTo(batched.getSourceMethodCounts());
        assertThat(streamed.getExceptionClassCounts()).isEqualTo(batched.getExceptionClassCounts());
    }

    @Test
    void collectorBuildsEmptyStatisticsWithoutInput() {
        assertThat(service.newCollector().build().getTotalCount()).isZero();
    }

    private static RawLogEntry entry(String line) {
        Instant timestamp = Instant.parse("2026-08-13T06:00:00Z");
        return new RawLogEntry(timestamp, Collections.<String, String>emptyMap(), line,
                new LogReference(timestamp, "sample-service"));
    }
}
