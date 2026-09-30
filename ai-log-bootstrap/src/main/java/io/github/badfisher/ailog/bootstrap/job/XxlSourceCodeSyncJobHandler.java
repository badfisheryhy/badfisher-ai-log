package io.github.badfisher.ailog.bootstrap.job;

import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.github.badfisher.ailog.application.sync.SourceCodeSyncJobService;
import io.github.badfisher.ailog.application.sync.SourceCodeSyncJobService.JobResult;

/**
 * XXL-JOB 生产源码独立同步入口。
 *
 * <p>固定同步 {@code prod} 环境中启用代码同步的全部模块，不接受调度参数。模块按仓储
 * 顺序串行执行，单模块失败不会阻断后续模块；任一模块失败时，全部模块执行完成后向
 * XXL-JOB 返回失败。日志同步中的单模块源码准备调用继续保留，作为缺失或失败时的补偿。</p>
 */
@Slf4j
@Component
public class XxlSourceCodeSyncJobHandler {

    /** 生产环境编码。 */
    private static final String PRODUCTION_ENVIRONMENT = "prod";

    private final SourceCodeSyncJobService service;

    /**
     * 创建生产源码同步处理器。
     *
     * @param jobService 源码同步任务服务
     */
    public XxlSourceCodeSyncJobHandler(SourceCodeSyncJobService jobService) {
        service = jobService;
    }

    /**
     * 同步生产环境全部启用模块的源码。
     *
     * @param ignoredParameter XXL-JOB 兼容参数，不参与执行
     * @return XXL-JOB 执行结果
     */
    @XxlJob("badfisherSourceCodeSyncJob")
    public ReturnT<String> syncProductionSourceCode(String ignoredParameter) {
        log.info("event=xxl_job_started XXL-JOB 开始执行：jobHandler=badfisherSourceCodeSyncJob");
        try {
            JobResult result = service.run(PRODUCTION_ENVIRONMENT);
            String summary = summary(result);
            if (result.getFailureCount() > 0) {
                log.error("event=xxl_source_code_sync_result_failed "
                                + "XXL-JOB 生产源码同步结果存在失败：summary={}",
                        summary);
                return new ReturnT<String>(ReturnT.FAIL_CODE, summary);
            }
            log.info("event=xxl_source_code_sync_completed "
                    + "XXL-JOB 生产源码同步完成：summary={}", summary);
            return new ReturnT<String>(ReturnT.SUCCESS_CODE, summary);
        } catch (Exception exception) {
            log.error("event=xxl_source_code_sync_execution_failed "
                    + "XXL-JOB 生产源码同步执行失败", exception);
            return new ReturnT<String>(ReturnT.FAIL_CODE, exception.getMessage());
        } finally {
            log.info("event=xxl_job_finished XXL-JOB 执行结束：jobHandler=badfisherSourceCodeSyncJob");
        }
    }

    /** 生成不包含仓库地址或凭据的任务汇总。 */
    private static String summary(JobResult result) {
        String value = "environment=" + PRODUCTION_ENVIRONMENT
                + ", total=" + result.getTotalCount()
                + ", success=" + result.getSuccessCount()
                + ", failure=" + result.getFailureCount();
        if (result.getFailedModules().isEmpty()) {
            return value;
        }
        return value + ", failedModules=" + result.getFailedModules();
    }
}
