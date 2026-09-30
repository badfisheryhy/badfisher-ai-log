package io.github.badfisher.ailog.bootstrap.job;

import java.time.LocalDate;
import java.time.ZoneId;

import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.badfisher.ailog.application.analysis.ErrorAnalysisJobService;
import io.github.badfisher.ailog.application.analysis.ErrorAnalysisJobService.JobResult;

/** XXL-JOB ERROR 文件解析入口。 */
@Slf4j
@Component
public class XxlErrorAnalysisJobHandler {
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final ErrorAnalysisJobService service;
    private final XxlJobParameterQueryRepository parameterRepository;
    private final ZoneId zone;

    public XxlErrorAnalysisJobHandler(ErrorAnalysisJobService analysisService,
            XxlJobParameterQueryRepository repository,
            @Value("${badfisher.timezone:" + DEFAULT_TIMEZONE + "}") String timezone) {
        service = analysisService;
        parameterRepository = repository;
        zone = ZoneId.of(timezone);
    }

    @XxlJob("badfisherErrorAnalysisJob")
    public ReturnT<String> analyzeErrorFiles(String rawParameter) {
        log.info("event=xxl_job_started XXL-JOB 开始执行：jobHandler=badfisherErrorAnalysisJob");
        try {
            XxlErrorAnalysisJobParameter parameter = parameterRepository
                    .queryErrorAnalysisParameter(rawParameter);
            LocalDate date = parameter.resolveDate(zone);
            String systemCode = parameter.getSystemCode();
            JobResult result = service.run(parameter.getEnvironment(), systemCode, date,
                    parameter.resolveMaxFiles());
            String summary = "date=" + date + ", systemCode="
                    + (systemCode == null ? "ALL" : systemCode)
                    + ", success=" + result.getSuccessCount()
                    + ", failure=" + result.getFailureCount();
            if (result.getFailureCount() > 0) {
                log.error("event=xxl_error_analysis_result_failed XXL-JOB ERROR 分析结果存在失败：summary={}",
                        summary);
                return new ReturnT<String>(ReturnT.FAIL_CODE, summary);
            }
            log.info("event=xxl_error_analysis_completed XXL-JOB ERROR 分析完成：summary={}", summary);
            return new ReturnT<String>(ReturnT.SUCCESS_CODE, summary);
        } catch (Exception ex) {
            log.error("event=xxl_error_analysis_execution_failed XXL-JOB ERROR 分析执行失败", ex);
            return new ReturnT<String>(ReturnT.FAIL_CODE, ex.getMessage());
        } finally {
            log.info("event=xxl_job_finished XXL-JOB 执行结束：jobHandler=badfisherErrorAnalysisJob");
        }
    }
}
