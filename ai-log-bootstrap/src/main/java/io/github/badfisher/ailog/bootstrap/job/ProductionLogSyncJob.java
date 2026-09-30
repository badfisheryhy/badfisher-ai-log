package io.github.badfisher.ailog.bootstrap.job;

import java.time.LocalDate;

import io.github.badfisher.ailog.application.command.BatchLogSyncCommand;
import io.github.badfisher.ailog.application.sync.BatchLogSyncResult;
import io.github.badfisher.ailog.application.sync.LogSyncApplicationService;
import io.github.badfisher.ailog.domain.sync.SyncTriggerType;

/**
 * 框架无关的 Java 任务处理器；可由外部调度或 XXL-JOB 适配器调用。
 */
public final class ProductionLogSyncJob {

    private final LogSyncApplicationService syncService;

    /**
     * 构造任务处理器。
     *
     * @param syncService 日志同步应用服务
     */
    public ProductionLogSyncJob(LogSyncApplicationService syncService) {
        this.syncService = syncService;
    }

    /**
     * 同步指定系统下所有启用模块。
     *
     * @param environment 环境编码
     * @param systemCode  系统编码
     * @param date        分析日期
     * @param force       是否强制全量同步
     * @return 批量同步结果
     */
    public BatchLogSyncResult syncSystem(String environment, String systemCode,
            LocalDate date, boolean force) {
        return syncService.syncEnabledModules(new BatchLogSyncCommand(environment, systemCode, date, force,
                SyncTriggerType.SCHEDULER));
    }

    /**
     * 在显式指定的原同步父任务上重试失败模块。
     *
     * @param taskId      原同步父任务 ID
     * @param environment 调度参数中的环境编码
     * @param systemCode  调度参数中的系统编码
     * @param force       是否强制全量同步
     * @return 本次实际重试模块的汇总
     */
    public BatchLogSyncResult retrySystem(long taskId, String environment,
            String systemCode, boolean force) {
        return syncService.retryFromScheduler(taskId, environment, systemCode, force);
    }
}
