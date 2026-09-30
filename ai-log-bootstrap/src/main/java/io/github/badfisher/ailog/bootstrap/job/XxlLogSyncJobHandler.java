package io.github.badfisher.ailog.bootstrap.job;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.badfisher.ailog.application.sync.BatchLogSyncResult;
import io.github.badfisher.ailog.application.sync.BatchModuleSyncResult;
import io.github.badfisher.ailog.ingestion.sync.LogSyncException;
import io.github.badfisher.ailog.ingestion.sync.SyncErrorCode;

/**
 * XXL-JOB 系统级生产日志同步入口。
 */
@Slf4j
@Component
public class XxlLogSyncJobHandler {

    /** 默认时区。 */
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    /** XXL 执行结果中的单模块错误消息上限。 */
    private static final int MODULE_ERROR_MESSAGE_LIMIT = 300;

    private final ProductionLogSyncJob job;
    private final XxlJobParameterQueryRepository parameterRepository;
    private final ZoneId zone;

    /**
     * 构造任务处理器。
     *
     * @param job      生产日志同步任务
     * @param repository XXL-JOB 参数查询仓储
     * @param timezone 时区，取自 {@code badfisher.timezone}，默认 {@code Asia/Shanghai}
     */
    public XxlLogSyncJobHandler(ProductionLogSyncJob job,
            XxlJobParameterQueryRepository repository,
            @Value("${badfisher.timezone:" + DEFAULT_TIMEZONE + "}") String timezone) {
        this.job = job;
        parameterRepository = repository;
        zone = ZoneId.of(timezone);
    }

    /**
     * 执行系统级日志同步。
     *
     * @param rawParameter 任务参数 JSON
     * @return XXL-JOB 执行结果
     */
    @XxlJob("badfisherSystemLogSyncJob")
    public ReturnT<String> badfisherSystemLogSyncJob(String rawParameter) {
        log.info("event=xxl_job_started XXL-JOB 开始执行：jobHandler=badfisherSystemLogSyncJob");
        try {
            XxlLogSyncJobParameter parameter = parameterRepository
                    .queryLogSyncParameter(rawParameter);
            return execute(parameter);
        } catch (Exception ex) {
            return completedWithFailure(ex);
        } finally {
            log.info("event=xxl_job_finished XXL-JOB 执行结束：jobHandler=badfisherSystemLogSyncJob");
        }
    }

    /** 执行显式配置的同步参数。 */
    private ReturnT<String> execute(XxlLogSyncJobParameter parameter) {
        try {
            String environment = parameter.getEnvironment();
            List<String> systemCodes = parameter.resolveSystemCodes();
            List<String> skippedSystems = new ArrayList<>();
            List<BatchModuleSyncResult> modules = new ArrayList<>();
            java.time.Duration elapsed = java.time.Duration.ZERO;
            String executionScope;
            if (parameter.hasTaskId()) {
                long taskId = parameter.resolveTaskId();
                BatchLogSyncResult result = retryTask(taskId, environment, systemCodes.get(0), parameter.isForce());
                modules.addAll(result.getModules());
                elapsed = elapsed.plus(result.getDuration());
                executionScope = "retryTaskId=" + taskId;
            } else {
                LocalDate date = parameter.resolveDate(zone);
                for (String systemCode : systemCodes) {
                    try {
                        BatchLogSyncResult result = syncNewTask(
                                environment, systemCode, date, parameter.isForce());
                        modules.addAll(result.getModules());
                        elapsed = elapsed.plus(result.getDuration());
                    } catch (LogSyncException ex) {
                        if (ex.getErrorCode() != SyncErrorCode.CONFIG_NOT_FOUND) {
                            throw ex;
                        }
                        skippedSystems.add(systemCode);
                        log.warn("event=xxl_log_sync_system_skipped_no_enabled_module "
                                        + "系统没有启用的日志模块，本次跳过：environment={}, "
                                        + "systemCode={}, date={}",
                                environment, systemCode, date);
                    }
                }
                executionScope = "date=" + date + ", systems=" + systemCodes
                        + ", skippedSystems=" + skippedSystems;
            }
            BatchLogSyncResult result = new BatchLogSyncResult(modules, elapsed);
            boolean businessFailed = result.getFailureCount() > 0L
                    || result.getConflictCount() > 0L;
            String summary = executionScope + ", businessStatus="
                    + (businessFailed ? "FAILED" : "SUCCESS")
                    + ", modules=" + result.getModules().size()
                    + ", success=" + result.getSuccessCount() + ", failure=" + result.getFailureCount()
                    + ", conflict=" + result.getConflictCount()
                    + ", failedModules=" + failedModules(result.getModules())
                    + ", durationMs=" + result.getDuration().toMillis();
            if (businessFailed) {
                log.error("event=xxl_production_log_sync_result_failed "
                                + "XXL-JOB 生产日志同步结果存在失败：summary={}",
                        summary);
                return new ReturnT<>(ReturnT.SUCCESS_CODE, summary);
            }
            log.info("event=xxl_production_log_sync_completed XXL-JOB 生产日志同步完成：summary={}",
                    summary);
            return new ReturnT<>(ReturnT.SUCCESS_CODE, summary);
        } catch (Exception ex) {
            return completedWithFailure(ex);
        }
    }

    /** 汇总失败模块及其具体错误，避免 XXL 页面只显示失败数量。 */
    private static List<String> failedModules(List<BatchModuleSyncResult> modules) {
        List<String> failures = new ArrayList<>();
        for (BatchModuleSyncResult module : modules) {
            if (module.isSuccess() || module.isConflict()) {
                continue;
            }
            String message = module.getMessage();
            if (message != null && message.length() > MODULE_ERROR_MESSAGE_LIMIT) {
                message = message.substring(0, MODULE_ERROR_MESSAGE_LIMIT);
            }
            failures.add(module.getModuleCode() + "(" + module.getErrorCode() + ":" + message + ")");
        }
        return failures;
    }

    /** 统一记录同步业务失败，同时保持 XXL-JOB 调度结果成功。 */
    private ReturnT<String> completedWithFailure(Exception exception) {
        if (exception instanceof LogSyncException
                && ((LogSyncException) exception).getErrorCode() == SyncErrorCode.SYNC_DISABLED) {
            String summary = "logSync=SKIPPED, reason=SYNC_DISABLED";
            log.info("event=xxl_production_log_sync_skipped 日志同步已主动关闭：summary={}", summary);
            return new ReturnT<>(ReturnT.SUCCESS_CODE, summary);
        }
        log.error("event=xxl_production_log_sync_execution_failed XXL-JOB 生产日志同步执行失败",
                exception);
        String errorCode = exception instanceof LogSyncException
                ? ((LogSyncException) exception).getErrorCode().name()
                : exception.getClass().getSimpleName();
        String message = exception.getMessage() == null
                ? "Unknown log sync failure" : exception.getMessage();
        if (message.length() > MODULE_ERROR_MESSAGE_LIMIT) {
            message = message.substring(0, MODULE_ERROR_MESSAGE_LIMIT);
        }
        String summary = "businessStatus=FAILED, errorCode=" + errorCode
                + ", errorMessage=" + message;
        return new ReturnT<>(ReturnT.SUCCESS_CODE, summary);
    }

    /** 保持旧参数路径按环境、系统、日期创建新父任务。 */
    BatchLogSyncResult syncNewTask(String environment, String systemCode,
            LocalDate date, boolean force) {
        return job.syncSystem(environment, systemCode, date, force);
    }

    /** 显式 taskId 路径只在原父任务上执行受控重试。 */
    BatchLogSyncResult retryTask(long taskId, String environment,
            String systemCode, boolean force) {
        return job.retrySystem(taskId, environment, systemCode, force);
    }
}
