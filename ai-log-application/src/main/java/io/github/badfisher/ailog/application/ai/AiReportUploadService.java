package io.github.badfisher.ailog.application.ai;

import static io.github.badfisher.ailog.application.report.MarkdownValues.value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import io.github.badfisher.ailog.application.tool.ObjectStorageTool;
import io.github.badfisher.ailog.domain.ai.AiTaskDeliveryReport;
import io.github.badfisher.ailog.domain.ai.AiTaskRepository;
import io.github.badfisher.ailog.domain.text.ExceptionMessages;

/** 领取已有分析结果的 AI 任务、生成 Markdown 并上传对象存储。 */
@Slf4j
public final class AiReportUploadService {

    private static final int MAX_UPLOADS_PER_RUN = 100;
    private static final int UPLOAD_STALE_MINUTES = 10;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    private final AiTaskRepository repository;
    private final ObjectStorageTool objectStorageTool;
    private final AiTaskReportMarkdownRenderer renderer;
    private final Clock clock;

    public AiReportUploadService(AiTaskRepository taskRepository,
            ObjectStorageTool storageTool,
            AiTaskReportMarkdownRenderer reportRenderer,
            Clock serviceClock) {
        if (taskRepository == null || reportRenderer == null || serviceClock == null) {
            throw new IllegalArgumentException(
                    "AI task repository, renderer and clock must not be null");
        }
        repository = taskRepository;
        objectStorageTool = storageTool;
        renderer = reportRenderer;
        clock = serviceClock;
    }

    /** 上传当前可领取报告；失败任务留待下次调度，不影响后续任务。 */
    public UploadResult uploadPending() {
        if (objectStorageTool == null) {
            return new UploadResult(0, 0);
        }
        int success = 0;
        int failure = 0;
        long afterTaskId = 0L;
        for (int index = 0; index < MAX_UPLOADS_PER_RUN; index++) {
            LocalDateTime currentTime = now();
            AiTaskDeliveryReport report = repository.claimNextDelivery(
                    afterTaskId, currentTime.minusMinutes(UPLOAD_STALE_MINUTES), currentTime);
            if (report == null) {
                break;
            }
            afterTaskId = report.getAiTaskId();
            String objectKey = null;
            try {
                objectKey = upload(report, renderer.render(report));
                if (repository.completeDelivery(report.getAiTaskId(), report.getClaimToken(),
                        objectKey, now())) {
                    success++;
                } else {
                    log.info("event=ai_task_report_upload_stale AI 任务报告上传期间结果已更新：aiTaskId={}",
                            Long.valueOf(report.getAiTaskId()));
                }
            } catch (Exception ex) {
                if (repository.failDelivery(report.getAiTaskId(), report.getClaimToken(),
                        objectKey, ExceptionMessages.singleLine(ex, ERROR_MESSAGE_MAX_LENGTH),
                        now())) {
                    failure++;
                    log.error("event=ai_task_report_upload_failed AI 任务报告上传失败：aiTaskId={}",
                            Long.valueOf(report.getAiTaskId()), ex);
                }
            }
        }
        return new UploadResult(success, failure);
    }

    private String upload(AiTaskDeliveryReport report, String markdown) throws IOException {
        Path temporaryFile = Files.createTempFile("ai-log-report-", ".md");
        try {
            Files.write(temporaryFile, markdown.getBytes(StandardCharsets.UTF_8));
            return objectStorageTool.upload(temporaryFile, objectKey(report));
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException ex) {
                log.warn("event=temporary_ai_report_delete_failed AI 临时报告删除失败：temporaryFile={}",
                        temporaryFile, ex);
            }
        }
    }

    private static String objectKey(AiTaskDeliveryReport report) {
        return "ai-analysis/" + safeSegment(report.getEnvironment()) + "/"
                + safeSegment(report.getSystemCode()) + "/" + value(report.getLogDate()) + "/"
                + safeSegment(report.getModuleCode()) + "/" + report.getAiTaskId() + ".md";
    }

    private static String safeSegment(String value) {
        String normalized = value(value).replaceAll("[^A-Za-z0-9._-]", "_");
        return "-".equals(normalized) ? "unknown" : normalized;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 单次对象存储报告上传结果。 */
    @Getter
    public static final class UploadResult {
        private final int successCount;
        private final int failureCount;

        public UploadResult(int success, int failure) {
            successCount = success;
            failureCount = failure;
        }
    }
}
