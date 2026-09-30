package io.github.badfisher.ailog.bootstrap.job;

import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.github.badfisher.ailog.application.cleanup.FileCleanupService;
import io.github.badfisher.ailog.application.cleanup.FileCleanupService.CleanupResult;
import io.github.badfisher.ailog.application.config.LogCleanupProperties;

/** 已解析日志文件的 XXL-JOB 补偿清理入口。 */
@Slf4j
@Component
public class XxlFileCleanupJobHandler {

    private final FileCleanupService cleanupService;
    private final LogCleanupProperties properties;
    private final XxlJobParameterQueryRepository parameterRepository;

    public XxlFileCleanupJobHandler(FileCleanupService service, LogCleanupProperties configuration,
            XxlJobParameterQueryRepository repository) {
        cleanupService = service;
        properties = configuration;
        parameterRepository = repository;
    }

    /**
     * 补偿清理已解析成功但尚未删除的 ready 文件。
     *
     * @param rawParameter XXL-JOB 参数 JSON；空参数使用配置批次大小
     * @return XXL-JOB 执行结果
     */
    @XxlJob("badfisherLogFileCleanupJob")
    public ReturnT<String> cleanupFiles(String rawParameter) {
        log.info("event=xxl_job_started XXL-JOB 开始执行：jobHandler=badfisherLogFileCleanupJob");
        try {
            XxlFileCleanupJobParameter parameter = parameterRepository
                    .queryFileCleanupParameter(rawParameter);
            CleanupResult result = cleanupService.cleanupPending(
                    parameter.resolveMaxFiles(properties.getBatchSize()));
            String summary = "success=" + result.getSuccessCount()
                    + ", failure=" + result.getFailureCount();
            if (result.getFailureCount() > 0) {
                log.error("event=xxl_local_file_cleanup_result_failed "
                                + "XXL-JOB 本地文件清理结果存在失败：summary={}",
                        summary);
                return new ReturnT<String>(ReturnT.FAIL_CODE, summary);
            }
            log.info("event=xxl_local_file_cleanup_completed XXL-JOB 本地文件清理完成：summary={}",
                    summary);
            return new ReturnT<String>(ReturnT.SUCCESS_CODE, summary);
        } catch (Exception ex) {
            log.error("event=xxl_local_file_cleanup_execution_failed XXL-JOB 本地文件清理执行失败", ex);
            return new ReturnT<String>(ReturnT.FAIL_CODE, ex.getMessage());
        } finally {
            log.info("event=xxl_job_finished XXL-JOB 执行结束：jobHandler=badfisherLogFileCleanupJob");
        }
    }
}
