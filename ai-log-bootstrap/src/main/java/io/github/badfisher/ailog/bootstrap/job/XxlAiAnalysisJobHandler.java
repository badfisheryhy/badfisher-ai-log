package io.github.badfisher.ailog.bootstrap.job;

import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.badfisher.ailog.application.ai.AiReportUploadService;
import io.github.badfisher.ailog.application.ai.AiReportUploadService.UploadResult;
import io.github.badfisher.ailog.application.ai.AiTaskJobService;
import io.github.badfisher.ailog.application.ai.AiTaskJobService.DispatchResult;
import io.github.badfisher.ailog.bootstrap.service.AiRerunExecutor;

/**
 * XXL-JOB AI Item 调度及 OSS 报告上传入口。
 *
 * <p>处理已有重跑补偿和普通 AI 队列后尝试上传报告，结束后不会自动发送钉钉。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
public class XxlAiAnalysisJobHandler {

    private final AiTaskJobService service;
    private final AiReportUploadService reportUploadService;
    private final ObjectProvider<AiRerunExecutor> rerunExecutors;

    public XxlAiAnalysisJobHandler(AiTaskJobService taskJobService,
            AiReportUploadService uploadService,
            ObjectProvider<AiRerunExecutor> executors) {
        service = taskJobService;
        reportUploadService = uploadService;
        rerunExecutors = executors;
    }

    @XxlJob("badfisherAiAnalysisJob")
    public ReturnT<String> dispatchAiItems(String ignoredParameter) {
        log.info("event=xxl_job_started XXL-JOB 开始执行：jobHandler=badfisherAiAnalysisJob");
        try {
            AiRerunExecutor rerunExecutor = rerunExecutors.getIfAvailable();
            int rerunRecoverySubmitted = rerunExecutor == null
                    ? 0 : rerunExecutor.compensateInterruptedOperations();
            DispatchResult result = service.dispatch();
            UploadResult upload = reportUploadService.uploadPending();
            String summary = "rerunRecoverySubmitted=" + rerunRecoverySubmitted
                    + ", prepared=" + result.getPreparedCount()
                    + ", recovered=" + result.getRecoveredCount()
                    + ", claimed=" + result.getClaimedCount()
                    + ", submitted=" + result.getSubmittedCount()
                    + ", rejected=" + result.getRejectedCount()
                    + ", success=" + result.getSuccessCount()
                    + ", failure=" + result.getFailureCount()
                    + ", retryScheduled=" + result.getRetryScheduledCount()
                    + ", reportUploaded=" + upload.getSuccessCount()
                    + ", reportUploadFailure=" + upload.getFailureCount();
            if (result.getRejectedCount() > 0 || upload.getFailureCount() > 0) {
                log.error("event=xxl_ai_analysis_result_failed XXL-JOB AI 分析结果存在失败：summary={}",
                        summary);
                return new ReturnT<String>(ReturnT.FAIL_CODE, summary);
            }
            log.info("event=xxl_ai_analysis_completed XXL-JOB AI 分析完成：summary={}", summary);
            return new ReturnT<String>(ReturnT.SUCCESS_CODE, summary);
        } catch (Exception ex) {
            log.error("event=xxl_ai_dispatch_failed XXL-JOB AI 调度执行失败", ex);
            return new ReturnT<String>(ReturnT.FAIL_CODE, ex.getMessage());
        } finally {
            log.info("event=xxl_job_finished XXL-JOB 执行结束：jobHandler=badfisherAiAnalysisJob");
        }
    }
}
