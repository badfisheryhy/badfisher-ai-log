package io.github.badfisher.ailog.domain.ai;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;

/** 已认领、等待生成文件并向外投递的 AI 批次报告。 */
@Getter
public final class AiTaskDeliveryReport {

    private final long aiTaskId;
    private final String claimToken;
    private final String taskNo;
    private final long analysisTaskId;
    private final String environment;
    private final String systemCode;
    private final String moduleCode;
    private final LocalDate logDate;
    private final String providerCode;
    private final String modelCode;
    private final String status;
    private final long candidateCount;
    private final int totalCount;
    private final int successCount;
    private final int failedCount;
    private final int totalAttemptCount;
    private final long totalTokenCount;
    private final List<AiTaskDeliveryItem> items;

    public AiTaskDeliveryReport(long taskId, String token, String number,
            long sourceAnalysisTaskId, String taskEnvironment, String taskSystemCode,
            String taskModuleCode, LocalDate taskLogDate, String provider, String model,
            String taskStatus, long candidates, int total, int successes, int failures,
            int attempts, long tokens, List<AiTaskDeliveryItem> resultItems) {
        aiTaskId = taskId;
        claimToken = token;
        taskNo = number;
        analysisTaskId = sourceAnalysisTaskId;
        environment = taskEnvironment;
        systemCode = taskSystemCode;
        moduleCode = taskModuleCode;
        logDate = taskLogDate;
        providerCode = provider;
        modelCode = model;
        status = taskStatus;
        candidateCount = candidates;
        totalCount = total;
        successCount = successes;
        failedCount = failures;
        totalAttemptCount = attempts;
        totalTokenCount = tokens;
        items = Collections.unmodifiableList(
                new ArrayList<AiTaskDeliveryItem>(resultItems));
    }

}
