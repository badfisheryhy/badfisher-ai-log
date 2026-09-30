package io.github.badfisher.ailog.application.ai;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import io.github.badfisher.ailog.application.ai.AiReportUploadService.UploadResult;
import io.github.badfisher.ailog.application.tool.ObjectStorageTool;
import io.github.badfisher.ailog.domain.ai.AiTaskDeliveryItem;
import io.github.badfisher.ailog.domain.ai.AiTaskDeliveryReport;
import io.github.badfisher.ailog.domain.ai.AiTaskRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiReportUploadServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 4, 0);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-31T20:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final String OBJECT_KEY =
            "ai-analysis/prod/demo/2026-08-31/order/11.md";

    @Test
    void shouldOnlyUploadOssReportAndCompleteAiTaskDelivery() throws Exception {
        assertUploadedObjectKey(taskReport(), OBJECT_KEY);
    }

    @Test
    void shouldPreserveObjectKeyNormalizationWithSharedFormatting() {
        AiTaskDeliveryReport report = taskReport(" prod\r\n ", " demo/\tcore ",
                " order-v1.2\r\n\tworker ", LocalDate.of(2026, 8, 31));

        assertUploadedObjectKey(report,
                "ai-analysis/prod/demo__core/2026-08-31/order-v1.2_worker/11.md");
    }

    @Test
    void shouldPreserveMissingObjectKeySegmentsAndDate() {
        AiTaskDeliveryReport report = taskReport(null, " \t\r\n ", "-", null);

        assertUploadedObjectKey(report, "ai-analysis/unknown/unknown/-/unknown/11.md");
    }

    @Test
    void shouldContinueWithNextCompletedReportAfterUploadFailure() {
        AiTaskRepository repository = mock(AiTaskRepository.class);
        ObjectStorageTool storage = mock(ObjectStorageTool.class);
        AiTaskDeliveryReport failedReport = taskReport();
        AiTaskDeliveryReport nextReport = new AiTaskDeliveryReport(
                12L, "next-token", "AIT-12", 3L, "prod", "demo", "order",
                LocalDate.of(2026, 8, 31), "openai", "gpt-5", "SUCCESS",
                17L, 17, 16, 0, 16, 3200L, Collections.emptyList());
        String nextKey = "ai-analysis/prod/demo/2026-08-31/order/12.md";
        when(repository.claimNextDelivery(eq(0L), any(LocalDateTime.class), eq(NOW)))
                .thenReturn(failedReport);
        when(repository.claimNextDelivery(eq(11L), any(LocalDateTime.class), eq(NOW)))
                .thenReturn(nextReport);
        when(repository.claimNextDelivery(eq(12L), any(LocalDateTime.class), eq(NOW)))
                .thenReturn(null);
        when(storage.upload(any(Path.class), eq(OBJECT_KEY)))
                .thenThrow(new IllegalStateException("upload unavailable"));
        when(storage.upload(any(Path.class), eq(nextKey))).thenReturn(nextKey);
        when(repository.failDelivery(eq(11L), eq("upload-token"), eq(null),
                any(String.class), eq(NOW))).thenReturn(true);
        when(repository.completeDelivery(12L, "next-token", nextKey, NOW))
                .thenReturn(true);

        UploadResult result = new AiReportUploadService(repository, storage,
                new AiTaskReportMarkdownRenderer(), CLOCK).uploadPending();

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailureCount()).isEqualTo(1);
        verify(storage).upload(any(Path.class), eq(nextKey));
        verify(repository).completeDelivery(12L, "next-token", nextKey, NOW);
    }

    @Test
    void shouldNotMarkChangedSnapshotAsDelivered() {
        AiTaskRepository repository = mock(AiTaskRepository.class);
        ObjectStorageTool storage = mock(ObjectStorageTool.class);
        when(repository.claimNextDelivery(eq(0L), any(LocalDateTime.class), eq(NOW)))
                .thenReturn(taskReport());
        when(storage.upload(any(Path.class), eq(OBJECT_KEY))).thenReturn(OBJECT_KEY);

        UploadResult result = new AiReportUploadService(repository, storage,
                new AiTaskReportMarkdownRenderer(), CLOCK).uploadPending();

        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getFailureCount()).isZero();
        verify(repository).completeDelivery(11L, "upload-token", OBJECT_KEY, NOW);
    }

    @Test
    void reportBodyOnlyContainsSuccessfulItemsAndExplainsOmittedFailures() {
        AiTaskDeliveryItem success = item("SUCCESS", "成功项", null, null);
        AiTaskDeliveryItem failure = item(
                "FAILED", "失败项不应出现", "RATE_LIMITED", "provider unavailable");
        AiTaskDeliveryReport report = new AiTaskDeliveryReport(
                11L, "upload-token", "AIT-11", 3L, "prod", "demo", "order",
                LocalDate.of(2026, 8, 31), "openai", "gpt-5", "SUCCESS",
                2L, 2, 1, 1, 2, 100L, Arrays.asList(success, failure));

        String markdown = new AiTaskReportMarkdownRenderer().render(report);

        assertThat(markdown)
                .contains("正文未展示失败 Item: 1", "成功项")
                .doesNotContain("失败项不应出现", "RATE_LIMITED", "provider unavailable");
        int core = markdown.indexOf("#### 核心结论");
        int details = markdown.indexOf("#### 诊断详情");
        assertThat(core).isGreaterThanOrEqualTo(0);
        assertThat(details).isGreaterThan(core);
        assertThat(markdown.substring(core, details))
                .contains("结论", "根因", "建议解决时间", "2 天", "置信度")
                .doesNotContain("支撑证据", "证据缺口");
    }

    @Test
    void reportWithOnlyFailedItemsStillHasFailureSummary() {
        AiTaskDeliveryItem failure = item(
                "FAILED", "失败项不应出现", "NETWORK_FAILURE", "provider unavailable");
        AiTaskDeliveryReport report = new AiTaskDeliveryReport(
                11L, "upload-token", "AIT-11", 3L, "prod", "demo", "order",
                LocalDate.of(2026, 8, 31), "openai", "gpt-5", "SUCCESS",
                1L, 1, 0, 1, 1, 100L, Collections.singletonList(failure));

        String markdown = new AiTaskReportMarkdownRenderer().render(report);

        assertThat(markdown)
                .contains("正文未展示失败 Item: 1", "无成功的 AI 分析结果")
                .doesNotContain("失败项不应出现", "NETWORK_FAILURE", "provider unavailable");
    }

    private static void assertUploadedObjectKey(AiTaskDeliveryReport report, String objectKey) {
        AiTaskRepository repository = mock(AiTaskRepository.class);
        ObjectStorageTool storage = mock(ObjectStorageTool.class);
        when(repository.claimNextDelivery(eq(0L), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(report);
        when(repository.claimNextDelivery(eq(report.getAiTaskId()),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(null);
        when(storage.upload(any(Path.class), eq(objectKey))).thenReturn(objectKey);
        when(repository.completeDelivery(report.getAiTaskId(), report.getClaimToken(),
                objectKey, NOW)).thenReturn(true);

        UploadResult result = new AiReportUploadService(repository, storage,
                new AiTaskReportMarkdownRenderer(), CLOCK).uploadPending();

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailureCount()).isZero();
        verify(storage).upload(any(Path.class), eq(objectKey));
        verify(repository).completeDelivery(11L, "upload-token", objectKey, NOW);
    }

    private static AiTaskDeliveryReport taskReport() {
        return taskReport("prod", "demo", "order", LocalDate.of(2026, 8, 31));
    }

    private static AiTaskDeliveryReport taskReport(String environment, String systemCode,
            String moduleCode, LocalDate logDate) {
        return new AiTaskDeliveryReport(11L, "upload-token", "AIT-11", 3L,
                environment, systemCode, moduleCode, logDate,
                "openai", "gpt-5", "SUCCESS", 17L, 17, 17, 0, 17, 3200L,
                Collections.emptyList());
    }

    private static AiTaskDeliveryItem item(String status, String title,
            String errorCode, String errorMessage) {
        return new AiTaskDeliveryItem(14L, 3L, status,
                "CONFIRMED", "HIGH", "CODE", title,
                "summary", "basis", "root cause", "impact",
                "recommendation", Integer.valueOf(2), "verification", "uncertainty", "rule",
                BigDecimal.valueOf(0.9D), true, errorCode, errorMessage);
    }
}
