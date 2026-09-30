package io.github.badfisher.ailog.bootstrap.recovery;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.transaction.annotation.Transactional;

import io.github.badfisher.ailog.domain.analysis.AnalysisTaskStatus;
import io.github.badfisher.ailog.domain.sync.LogFileParseStatus;
import io.github.badfisher.ailog.domain.sync.LogFileSyncStatus;
import io.github.badfisher.ailog.domain.sync.LogFileType;
import io.github.badfisher.ailog.domain.sync.SyncTaskStatus;
import io.github.badfisher.ailog.ingestion.local.LocalLogFileValidator;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogAnalysisTaskEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogAnalysisTaskMapper;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogFileRecordEntity;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogSyncModuleTaskEntity;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogSyncTaskEntity;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogFileRecordMapper;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogSyncModuleTaskMapper;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogSyncTaskMapper;

/** 单实例启动恢复的短事务服务；每次调用只处理一个父任务或一个解析任务。 */
public class StartupRecoveryTransactionService {

    private static final String SYNC_RECOVERY_ERROR =
            "应用启动恢复：遗留同步任务已收口失败，未自动重新下载";

    /** 同步父任务 Mapper。 */
    private final AiLogSyncTaskMapper syncTaskMapper;
    /** 同步模块 Mapper。 */
    private final AiLogSyncModuleTaskMapper moduleTaskMapper;
    /** 文件记录 Mapper。 */
    private final AiLogFileRecordMapper fileRecordMapper;
    /** 解析任务 Mapper。 */
    private final AiLogAnalysisTaskMapper analysisTaskMapper;
    /** 本地日志根目录。 */
    private final Path rootDirectory;
    /** 当前可领取的指纹版本。 */
    private final String fingerprintVersion;

    /** 创建启动恢复事务服务。 */
    public StartupRecoveryTransactionService(AiLogSyncTaskMapper syncTasks,
            AiLogSyncModuleTaskMapper moduleTasks, AiLogFileRecordMapper files,
            AiLogAnalysisTaskMapper analysisTasks, String rootDirectoryValue,
            String currentFingerprintVersion) {
        syncTaskMapper = syncTasks;
        moduleTaskMapper = moduleTasks;
        fileRecordMapper = files;
        analysisTaskMapper = analysisTasks;
        rootDirectory = Paths.get(rootDirectoryValue).toAbsolutePath().normalize();
        fingerprintVersion = currentFingerprintVersion;
    }

    /** 将一个遗留 PENDING/RUNNING 同步父任务及未完成子记录一致收口为 FAILED。 */
    @Transactional(rollbackFor = Exception.class)
    public void recoverSyncTask(long taskId) {
        AiLogSyncTaskEntity task = syncTaskMapper.selectById(Long.valueOf(taskId));
        if (task == null || !isUnfinishedSync(task.getStatus())) {
            return;
        }

        List<AiLogFileRecordEntity> files = fileRecordMapper.selectList(
                Wrappers.<AiLogFileRecordEntity>lambdaQuery()
                        .eq(AiLogFileRecordEntity::getSyncTaskId, Long.valueOf(taskId)));
        Map<Long, FileCounts> countsByModule = summarizeFiles(files);
        for (AiLogFileRecordEntity file : files) {
            if (LogFileSyncStatus.PENDING.name().equals(file.getSyncStatus())
                    || LogFileSyncStatus.SYNCING.name().equals(file.getSyncStatus())) {
                int fileUpdated = fileRecordMapper.update(null,
                        Wrappers.<AiLogFileRecordEntity>lambdaUpdate()
                                .eq(AiLogFileRecordEntity::getId, file.getId())
                                .in(AiLogFileRecordEntity::getSyncStatus,
                                        LogFileSyncStatus.PENDING.name(),
                                        LogFileSyncStatus.SYNCING.name())
                                .set(AiLogFileRecordEntity::getSyncStatus,
                                        LogFileSyncStatus.FAILED.name())
                                .set(AiLogFileRecordEntity::getErrorMessage,
                                        SYNC_RECOVERY_ERROR));
                if (fileUpdated != 1) {
                    throw new IllegalStateException(
                            "启动恢复时同步文件状态已变化：" + file.getId());
                }
            }
        }

        List<AiLogSyncModuleTaskEntity> modules = moduleTaskMapper.selectList(
                Wrappers.<AiLogSyncModuleTaskEntity>lambdaQuery()
                        .eq(AiLogSyncModuleTaskEntity::getSyncTaskId, Long.valueOf(taskId)));
        int successModules = 0;
        for (AiLogSyncModuleTaskEntity module : modules) {
            if (SyncTaskStatus.SUCCESS.name().equals(module.getStatus())) {
                successModules++;
                continue;
            }
            if (isUnfinishedSync(module.getStatus())) {
                FileCounts counts = countsByModule.get(module.getId());
                int totalFiles = counts == null ? 0 : counts.total;
                int readyFiles = counts == null ? 0 : counts.ready;
                AiLogSyncModuleTaskEntity update = new AiLogSyncModuleTaskEntity();
                update.setStatus(SyncTaskStatus.FAILED.name());
                update.setTotalFileCount(Integer.valueOf(totalFiles));
                update.setSuccessFileCount(Integer.valueOf(readyFiles));
                update.setFailedFileCount(Integer.valueOf(totalFiles - readyFiles));
                update.setFinishTime(LocalDateTime.now());
                update.setErrorMessage(SYNC_RECOVERY_ERROR);
                int moduleUpdated = moduleTaskMapper.update(update,
                        Wrappers.<AiLogSyncModuleTaskEntity>lambdaUpdate()
                                .eq(AiLogSyncModuleTaskEntity::getId, module.getId())
                                .in(AiLogSyncModuleTaskEntity::getStatus,
                                        SyncTaskStatus.PENDING.name(),
                                        SyncTaskStatus.RUNNING.name()));
                if (moduleUpdated != 1) {
                    throw new IllegalStateException(
                            "启动恢复时同步模块状态已变化：" + module.getId());
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int taskUpdated = syncTaskMapper.update(null,
                Wrappers.<AiLogSyncTaskEntity>lambdaUpdate()
                .eq(AiLogSyncTaskEntity::getId, Long.valueOf(taskId))
                .in(AiLogSyncTaskEntity::getStatus, SyncTaskStatus.PENDING.name(),
                        SyncTaskStatus.RUNNING.name())
                .set(AiLogSyncTaskEntity::getStatus, SyncTaskStatus.FAILED.name())
                .set(AiLogSyncTaskEntity::getTotalModuleCount,
                        Integer.valueOf(modules.size()))
                .set(AiLogSyncTaskEntity::getSuccessModuleCount,
                        Integer.valueOf(successModules))
                .set(AiLogSyncTaskEntity::getFailedModuleCount,
                        Integer.valueOf(modules.size() - successModules))
                .set(AiLogSyncTaskEntity::getFinishTime, now)
                .set(AiLogSyncTaskEntity::getHeartbeatTime, now)
                .set(AiLogSyncTaskEntity::getErrorMessage, SYNC_RECOVERY_ERROR));
        if (taskUpdated != 1) {
            throw new IllegalStateException("启动恢复时同步父任务状态已变化：" + taskId);
        }
    }

    /** 恢复一个遗留 PARSING 任务；只有文件与版本仍安全兼容时才回到 WAITING。 */
    @Transactional(rollbackFor = Exception.class)
    public void recoverAnalysisTask(long analysisTaskId) {
        AiLogAnalysisTaskEntity task = analysisTaskMapper.selectById(
                Long.valueOf(analysisTaskId));
        if (task == null || !AnalysisTaskStatus.PARSING.name().equals(task.getStatus())) {
            return;
        }
        if (task.getFileRecordId() == null) {
            recoverDirectAnalysisTask(task);
            return;
        }
        AiLogFileRecordEntity file = fileRecordMapper.selectById(task.getFileRecordId());
        String invalidReason = validateRecoverableFile(task, file);
        if (invalidReason == null) {
            resetAnalysisToWaiting(task, file);
        } else {
            failAnalysisRecovery(task, file, invalidReason);
        }
    }

    /** 恢复不依赖 FileRecord 的 DIRECT PARSING 任务。 */
    private void recoverDirectAnalysisTask(AiLogAnalysisTaskEntity task) {
        if (!fingerprintVersion.equals(task.getFingerprintVersion())) {
            failAnalysisRecovery(task, null,
                    "应用启动恢复失败：解析任务指纹版本与当前版本不兼容");
            return;
        }
        resetAnalysisTaskToWaiting(task);
    }

    /** 汇总同步文件实际总数和 READY 数。 */
    private static Map<Long, FileCounts> summarizeFiles(List<AiLogFileRecordEntity> files) {
        Map<Long, FileCounts> result = new HashMap<Long, FileCounts>();
        for (AiLogFileRecordEntity file : files) {
            Long moduleTaskId = file.getSyncModuleTaskId();
            FileCounts counts = result.get(moduleTaskId);
            if (counts == null) {
                counts = new FileCounts();
                result.put(moduleTaskId, counts);
            }
            counts.total++;
            if (LogFileSyncStatus.READY.name().equals(file.getSyncStatus())) {
                counts.ready++;
            }
        }
        return result;
    }

    /** 校验解析恢复所需的任务版本、文件状态和本地路径。 */
    private String validateRecoverableFile(AiLogAnalysisTaskEntity task,
            AiLogFileRecordEntity file) {
        if (!fingerprintVersion.equals(task.getFingerprintVersion())) {
            return "应用启动恢复失败：解析任务指纹版本与当前版本不兼容";
        }
        if (file == null) {
            return "应用启动恢复失败：解析任务对应的文件记录不存在";
        }
        if (!LogFileType.ERROR.name().equals(file.getFileType())
                || !LogFileSyncStatus.READY.name().equals(file.getSyncStatus())
                || !LogFileParseStatus.PARSING.name().equals(file.getParseStatus())) {
            return "应用启动恢复失败：文件必须为ERROR/READY/PARSING状态";
        }
        if (file.getLocalPath() == null || file.getLocalPath().trim().isEmpty()) {
            return "应用启动恢复失败：本地文件路径为空";
        }
        Path localPath;
        try {
            localPath = Paths.get(file.getLocalPath()).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return "应用启动恢复失败：本地文件路径无效";
        }
        if (!localPath.startsWith(rootDirectory)) {
            return "应用启动恢复失败：本地文件不在配置的日志根目录下";
        }
        try {
            LocalLogFileValidator.requireReadableFile(localPath);
        } catch (RuntimeException exception) {
            return "应用启动恢复失败：" + exception.getMessage();
        }
        return null;
    }

    /** 将安全可重试的同一 Task/File 原子恢复为 WAITING。 */
    private void resetAnalysisToWaiting(AiLogAnalysisTaskEntity task,
            AiLogFileRecordEntity file) {
        resetAnalysisTaskToWaiting(task);
        int fileUpdated = fileRecordMapper.update(null,
                Wrappers.<AiLogFileRecordEntity>lambdaUpdate()
                        .eq(AiLogFileRecordEntity::getId, file.getId())
                        .eq(AiLogFileRecordEntity::getSyncStatus,
                                LogFileSyncStatus.READY.name())
                        .eq(AiLogFileRecordEntity::getParseStatus,
                                LogFileParseStatus.PARSING.name())
                        .set(AiLogFileRecordEntity::getParseStatus,
                                LogFileParseStatus.WAITING.name())
                        .set(AiLogFileRecordEntity::getParseTime, null)
                        .set(AiLogFileRecordEntity::getErrorMessage, null));
        if (fileUpdated != 1) {
            throw new IllegalStateException("启动恢复时文件状态已变化：" + file.getId());
        }
    }

    /** 将 PARSING 解析任务恢复为可重新认领的 WAITING 状态。 */
    private void resetAnalysisTaskToWaiting(AiLogAnalysisTaskEntity task) {
        int taskUpdated = analysisTaskMapper.update(null,
                Wrappers.<AiLogAnalysisTaskEntity>lambdaUpdate()
                        .eq(AiLogAnalysisTaskEntity::getId, task.getId())
                        .eq(AiLogAnalysisTaskEntity::getStatus,
                                AnalysisTaskStatus.PARSING.name())
                        .set(AiLogAnalysisTaskEntity::getStatus,
                                AnalysisTaskStatus.WAITING.name())
                        .set(AiLogAnalysisTaskEntity::getStartTime, null)
                        .set(AiLogAnalysisTaskEntity::getFinishTime, null)
                        .set(AiLogAnalysisTaskEntity::getHeartbeatTime, null)
                        .set(AiLogAnalysisTaskEntity::getErrorMessage, null));
        if (taskUpdated != 1) {
            throw new IllegalStateException("启动恢复时解析任务状态已变化：" + task.getId());
        }
    }

    /** 将不可安全恢复的解析任务及仍在 PARSING 的文件明确收口为 FAILED。 */
    private void failAnalysisRecovery(AiLogAnalysisTaskEntity task,
            AiLogFileRecordEntity file, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        int taskUpdated = analysisTaskMapper.update(null,
                Wrappers.<AiLogAnalysisTaskEntity>lambdaUpdate()
                        .eq(AiLogAnalysisTaskEntity::getId, task.getId())
                        .eq(AiLogAnalysisTaskEntity::getStatus,
                                AnalysisTaskStatus.PARSING.name())
                        .set(AiLogAnalysisTaskEntity::getStatus,
                                AnalysisTaskStatus.FAILED.name())
                        .set(AiLogAnalysisTaskEntity::getFinishTime, now)
                        .set(AiLogAnalysisTaskEntity::getHeartbeatTime, now)
                        .set(AiLogAnalysisTaskEntity::getErrorMessage, errorMessage));
        if (taskUpdated != 1) {
            throw new IllegalStateException("启动恢复时解析任务状态已变化：" + task.getId());
        }
        if (file != null && LogFileParseStatus.PARSING.name().equals(file.getParseStatus())) {
            int fileUpdated = fileRecordMapper.update(null,
                    Wrappers.<AiLogFileRecordEntity>lambdaUpdate()
                            .eq(AiLogFileRecordEntity::getId, file.getId())
                            .eq(AiLogFileRecordEntity::getParseStatus,
                                    LogFileParseStatus.PARSING.name())
                            .set(AiLogFileRecordEntity::getParseStatus,
                                    LogFileParseStatus.FAILED.name())
                            .set(AiLogFileRecordEntity::getParseTime, now)
                            .set(AiLogFileRecordEntity::getErrorMessage, errorMessage));
            if (fileUpdated != 1) {
                throw new IllegalStateException(
                        "启动恢复时解析文件状态已变化：" + file.getId());
            }
        }
    }

    private static boolean isUnfinishedSync(String status) {
        return SyncTaskStatus.PENDING.name().equals(status)
                || SyncTaskStatus.RUNNING.name().equals(status);
    }

    /** 模块文件实际计数。 */
    private static final class FileCounts {
        private int total;
        private int ready;
    }
}
