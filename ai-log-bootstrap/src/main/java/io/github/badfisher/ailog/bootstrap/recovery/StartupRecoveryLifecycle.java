package io.github.badfisher.ailog.bootstrap.recovery;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import io.github.badfisher.ailog.bootstrap.config.StartupRecoveryProperties;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogAnalysisTaskEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogAnalysisTaskMapper;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogSyncTaskEntity;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogSyncTaskMapper;

/** Bean 初始化阶段同步执行的单实例恢复协调器。 */
@Slf4j
public class StartupRecoveryLifecycle implements InitializingBean {

    /** 同步任务 Mapper。 */
    private final AiLogSyncTaskMapper syncTaskMapper;
    /** 解析任务 Mapper。 */
    private final AiLogAnalysisTaskMapper analysisTaskMapper;
    /** 通过 Spring 代理调用的短事务服务。 */
    private final StartupRecoveryTransactionService transactionService;
    /** 恢复开关和责任范围。 */
    private final StartupRecoveryProperties properties;
    /** 防止容器重复调用或测试误触发导致重复恢复。 */
    private boolean recovered;

    /** 创建恢复生命周期。 */
    public StartupRecoveryLifecycle(AiLogSyncTaskMapper syncTasks,
            AiLogAnalysisTaskMapper analysisTasks,
            StartupRecoveryTransactionService transactions,
            StartupRecoveryProperties recoveryProperties) {
        syncTaskMapper = syncTasks;
        analysisTaskMapper = analysisTasks;
        transactionService = transactions;
        properties = recoveryProperties;
    }

    /** 顺序恢复遗留同步父任务和解析任务，任一事务失败则阻止后续 Bean 初始化。 */
    @Override
    public synchronized void afterPropertiesSet() {
        if (recovered) {
            return;
        }
        if (!properties.isEnabled()) {
            recovered = true;
            return;
        }
        validateScope();
        int syncTaskCount = recoverSyncTasks();
        int analysisTaskCount = recoverAnalysisTasks();
        recovered = true;
        log.info("event=startup_recovery_completed 单实例启动恢复完成：environment={}, "
                        + "systemCode={}, syncTaskCount={}, analysisTaskCount={}",
                properties.getEnvironment(), properties.getSystemCode(), syncTaskCount,
                analysisTaskCount);
    }

    /** 按主键游标分批恢复责任范围内的遗留同步任务。 */
    private int recoverSyncTasks() {
        long lastId = 0L;
        int recovered = 0;
        while (true) {
            List<AiLogSyncTaskEntity> tasks = syncTaskMapper.selectRecoveryBatch(
                    Long.valueOf(lastId), properties.getEnvironment(),
                    properties.getSystemCode(), properties.getBatchSize());
            if (tasks.isEmpty()) {
                return recovered;
            }
            for (AiLogSyncTaskEntity task : tasks) {
                transactionService.recoverSyncTask(task.getId().longValue());
                lastId = task.getId().longValue();
                recovered++;
            }
        }
    }

    /** 按主键游标分批恢复责任范围内的遗留解析任务。 */
    private int recoverAnalysisTasks() {
        long lastId = 0L;
        int recovered = 0;
        while (true) {
            List<AiLogAnalysisTaskEntity> tasks = analysisTaskMapper.selectRecoveryBatch(
                    Long.valueOf(lastId), properties.getEnvironment(),
                    properties.getSystemCode(), properties.getBatchSize());
            if (tasks.isEmpty()) {
                return recovered;
            }
            for (AiLogAnalysisTaskEntity task : tasks) {
                transactionService.recoverAnalysisTask(task.getId().longValue());
                lastId = task.getId().longValue();
                recovered++;
            }
        }
    }

    /** 开启恢复时必须显式限定环境、系统并提供合理批次。 */
    private void validateScope() {
        if (!hasText(properties.getEnvironment()) || !hasText(properties.getSystemCode())) {
            throw new IllegalStateException("启动恢复已开启，必须配置badfisher.recovery."
                    + "environment和system-code，并确认旧应用进程已经停止");
        }
        if (properties.getBatchSize() < 1 || properties.getBatchSize() > 1000) {
            throw new IllegalStateException("badfisher.recovery.batch-size必须在1到1000之间");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 是否已经跳过或成功完成恢复；异常时保持 false。 */
    public synchronized boolean isRecovered() {
        return recovered;
    }
}
