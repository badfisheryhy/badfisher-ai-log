package io.github.badfisher.ailog.bootstrap.job;

import java.time.LocalDate;
import java.time.ZoneId;

import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.badfisher.ailog.application.ai.AiReportUploadService;
import io.github.badfisher.ailog.application.ai.AiReportUploadService.UploadResult;
import io.github.badfisher.ailog.application.ai.AiTaskJobService;
import io.github.badfisher.ailog.application.ai.AiTaskJobService.DispatchResult;
import io.github.badfisher.ailog.application.analysis.ErrorAnalysisJobService;
import io.github.badfisher.ailog.application.analysis.ErrorAnalysisJobService.JobResult;
import io.github.badfisher.ailog.application.cleanup.FileCleanupService;
import io.github.badfisher.ailog.application.cleanup.FileCleanupService.CleanupResult;
import io.github.badfisher.ailog.bootstrap.service.AiRerunExecutor;
import io.github.badfisher.ailog.domain.text.ExceptionMessages;

/**
 * 处理指定业务日期的解析、AI 调用、OSS 报告上传和补偿清理。
 *
 * <p>日志同步由独立同步任务预先完成。本入口依次处理文件解析、AI 调用、OSS 报告上传
 * 和文件清理，并在 AI 调度前提交已有重跑操作的补偿。</p>
 *
 * <p>本流程结束后不会自动发送机器人消息。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
public class XxlDailyLogAnalysisJobHandler {

    private final ErrorAnalysisJobService analysisService;
    private final AiTaskJobService aiTaskService;
    private final AiReportUploadService reportUploadService;
    private final FileCleanupService cleanupService;
    private final XxlJobParameterQueryRepository parameterRepository;
    private final ObjectProvider<AiRerunExecutor> rerunExecutors;
    private final ZoneId zone;

    public XxlDailyLogAnalysisJobHandler(ErrorAnalysisJobService errorAnalysisService,
            AiTaskJobService taskJobService,
            AiReportUploadService uploadService,
            FileCleanupService fileCleanupService,
            XxlJobParameterQueryRepository parameters,
            ObjectProvider<AiRerunExecutor> executors,
            @Value("${badfisher.timezone:Asia/Shanghai}") String timezone) {
        analysisService = errorAnalysisService;
        aiTaskService = taskJobService;
        reportUploadService = uploadService;
        cleanupService = fileCleanupService;
        parameterRepository = parameters;
        rerunExecutors = executors;
        zone = ZoneId.of(timezone);
    }

    /**
     * 各阶段独立执行并记录结果；某一阶段失败不会阻止后续补偿阶段处理已有数据。
     */
    @XxlJob("badfisherDailyLogAnalysisJob")
    public ReturnT<String> executeDailyFlow(String rawParameter) {
        log.info("event=xxl_job_started XXL-JOB 开始执行：jobHandler=badfisherDailyLogAnalysisJob");
        final XxlDailyLogAnalysisJobParameter parameter;
        final LocalDate date;
        try {
            parameter = parameterRepository.queryDailyLogAnalysisParameter(rawParameter);
            date = parameter.resolveDate(zone);
        } catch (Exception ex) {
            log.error("event=xxl_daily_log_parameter_invalid XXL-JOB 每日日志处理参数无效", ex);
            log.info("event=xxl_job_finished XXL-JOB 执行结束：jobHandler=badfisherDailyLogAnalysisJob");
            return new ReturnT<String>(ReturnT.FAIL_CODE, ExceptionMessages.singleLine(ex));
        }

        StringBuilder summary = new StringBuilder(384);
        summary.append("date=").append(date);
        boolean failed = false;

        try {
            JobResult analysis = analysisService.run(parameter.getEnvironment(),
                    parameter.getSystemCode(), date, parameter.resolveMaxFiles());
            summary.append(", analysisSuccess=").append(analysis.getSuccessCount())
                    .append(", analysisFailure=").append(analysis.getFailureCount());
            failed = analysis.getFailureCount() > 0;
        } catch (Exception ex) {
            failed = true;
            appendStageError(summary, "analysis", ex);
            log.error("event=xxl_daily_analysis_stage_failed XXL-JOB 每日分析阶段失败：date={}",
                    date, ex);
        }

        try {
            AiRerunExecutor rerunExecutor = rerunExecutors.getIfAvailable();
            int submitted = rerunExecutor == null
                    ? 0 : rerunExecutor.compensateInterruptedOperations();
            summary.append(", rerunRecoverySubmitted=").append(submitted);
        } catch (Exception ex) {
            failed = true;
            appendStageError(summary, "aiRerunRecovery", ex);
            log.error("event=xxl_daily_ai_rerun_recovery_failed XXL-JOB 重跑补偿提交失败：date={}",
                    date, ex);
        }

        try {
            DispatchResult ai = aiTaskService.dispatch();
            summary.append(", aiSuccess=").append(ai.getSuccessCount())
                    .append(", aiFailure=").append(ai.getFailureCount())
                    .append(", aiRetryScheduled=").append(ai.getRetryScheduledCount())
                    .append(", aiRejected=").append(ai.getRejectedCount());
            failed = failed || ai.getRejectedCount() > 0;
        } catch (Exception ex) {
            failed = true;
            appendStageError(summary, "ai", ex);
            log.error("event=xxl_daily_ai_stage_failed XXL-JOB 每日 AI 阶段失败：date={}",
                    date, ex);
        }

        try {
            UploadResult upload = reportUploadService.uploadPending();
            summary.append(", reportUploaded=").append(upload.getSuccessCount())
                    .append(", reportUploadFailure=").append(upload.getFailureCount());
            failed = failed || upload.getFailureCount() > 0;
        } catch (Exception ex) {
            failed = true;
            appendStageError(summary, "oss", ex);
            log.error("event=xxl_daily_oss_upload_stage_failed XXL-JOB 每日 OSS 报告上传阶段失败：date={}",
                    date, ex);
        }

        try {
            CleanupResult cleanup = cleanupService.cleanupPending(
                    parameter.resolveCleanupMaxFiles());
            summary.append(", cleanupSuccess=").append(cleanup.getSuccessCount())
                    .append(", cleanupFailure=").append(cleanup.getFailureCount());
            failed = failed || cleanup.getFailureCount() > 0;
        } catch (Exception ex) {
            failed = true;
            appendStageError(summary, "cleanup", ex);
            log.error("event=xxl_daily_cleanup_stage_failed XXL-JOB 每日清理补偿阶段失败：date={}",
                    date, ex);
        }

        String message = summary.toString();
        if (failed) {
            log.error("event=xxl_daily_log_processing_failed XXL-JOB 每日日志处理存在失败：summary={}",
                    message);
            log.info("event=xxl_job_finished XXL-JOB 执行结束：jobHandler=badfisherDailyLogAnalysisJob");
            return new ReturnT<String>(ReturnT.FAIL_CODE, message);
        }
        log.info("event=xxl_daily_log_processing_completed XXL-JOB 每日日志处理完成：summary={}",
                message);
        log.info("event=xxl_job_finished XXL-JOB 执行结束：jobHandler=badfisherDailyLogAnalysisJob");
        return new ReturnT<String>(ReturnT.SUCCESS_CODE, message);
    }

    private static void appendStageError(StringBuilder summary,
            String stage,
            Exception exception) {
        summary.append(", ").append(stage).append("Error=")
                .append(ExceptionMessages.singleLine(exception));
    }
}
