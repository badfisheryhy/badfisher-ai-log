package io.github.badfisher.ailog.persistence.workflow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.badfisher.ailog.domain.analysis.AnalysisTaskStatus;
import io.github.badfisher.ailog.domain.cleanup.FileCleanupStatus;
import io.github.badfisher.ailog.domain.cleanup.FileCleanupTriggerReason;
import io.github.badfisher.ailog.domain.sync.LogChannel;
import io.github.badfisher.ailog.domain.sync.LogFileParseStatus;
import io.github.badfisher.ailog.domain.sync.LogFileSyncStatus;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.ModuleOutcomeSummary;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.RetryTask;
import io.github.badfisher.ailog.domain.sync.SyncTaskStatus;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogAnalysisTaskEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogAnalysisTaskMapper;
import io.github.badfisher.ailog.persistence.cleanup.entity.AiLogFileCleanupEntity;
import io.github.badfisher.ailog.persistence.cleanup.mapper.AiLogFileCleanupMapper;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogFileRecordEntity;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogSyncModuleTaskEntity;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogSyncTaskEntity;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogFileRecordMapper;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogSyncModuleTaskMapper;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogSyncTaskMapper;

/** MyBatis-Plus同步任务聚合仓储。 */
public class MybatisPlusLogSyncTaskRepository implements LogSyncTaskRepository {

    private static final int CLAIM_SCAN_LIMIT = 20;

    /** 同步任务 Mapper。 */
    private final AiLogSyncTaskMapper taskMapper;
    /** 模块子任务 Mapper。 */
    private final AiLogSyncModuleTaskMapper moduleMapper;
    /** 文件记录 Mapper。 */
    private final AiLogFileRecordMapper fileMapper;
    /** ERROR 分析任务 Mapper。 */
    private final AiLogAnalysisTaskMapper analysisTaskMapper;
    /** 文件清理任务 Mapper。 */
    private final AiLogFileCleanupMapper cleanupMapper;
    /** 当前分析指纹版本。 */
    private final String fingerprintVersion;

    /**
     * 构造同步任务聚合仓储。
     *
     * @param tasks   同步任务 Mapper
     * @param modules 模块子任务 Mapper
     * @param files   文件记录 Mapper
     */
    public MybatisPlusLogSyncTaskRepository(AiLogSyncTaskMapper tasks,
            AiLogSyncModuleTaskMapper modules, AiLogFileRecordMapper files,
            AiLogAnalysisTaskMapper analysisTasks, AiLogFileCleanupMapper cleanups,
            String currentFingerprintVersion) {
        taskMapper = tasks;
        moduleMapper = modules;
        fileMapper = files;
        analysisTaskMapper = analysisTasks;
        cleanupMapper = cleanups;
        fingerprintVersion = currentFingerprintVersion;
    }

    /**
     * 加载可重试任务及其模块任务映射。
     * <p>模块映射只包含 FAILED 或 CONFLICT 模块：已成功模块不参与重跑，非终态模块拒绝重置。</p>
     *
     * @param taskId 任务 ID
     * @return 重试任务聚合
     * @throws IllegalArgumentException 任务不存在时抛出
     * @throws IllegalStateException    任务状态不可重试时抛出
     */
    @Override
    public RetryTask loadRetryTask(long taskId) {
        AiLogSyncTaskEntity task = taskMapper.selectById(Long.valueOf(taskId));
        if (task == null) {
            throw new IllegalArgumentException("同步父任务不存在：taskId=" + taskId);
        }
        if (!SyncTaskStatus.FAILED.name().equals(task.getStatus())
                && !SyncTaskStatus.PARTIAL_SUCCESS.name().equals(task.getStatus())
                && !SyncTaskStatus.CONFLICT.name().equals(task.getStatus())) {
            throw new IllegalStateException(
                    "同步父任务状态不可重试，仅FAILED、PARTIAL_SUCCESS或CONFLICT允许重试：taskId="
                            + taskId + ", status=" + task.getStatus());
        }
        List<AiLogSyncModuleTaskEntity> modules = moduleMapper.selectList(
                Wrappers.<AiLogSyncModuleTaskEntity>lambdaQuery()
                        .eq(AiLogSyncModuleTaskEntity::getSyncTaskId, Long.valueOf(taskId)));
        Map<String, Long> moduleIds = new LinkedHashMap<>();
        for (AiLogSyncModuleTaskEntity module : modules) {
            if (SyncTaskStatus.SUCCESS.name().equals(module.getStatus())) {
                continue;
            }
            if (!SyncTaskStatus.FAILED.name().equals(module.getStatus())
                    && !SyncTaskStatus.CONFLICT.name().equals(module.getStatus())) {
                throw new IllegalStateException("同步模块状态不可重试，拒绝重置RUNNING或未完成状态：taskId="
                        + taskId + ", moduleCode=" + module.getModuleCode()
                        + ", status=" + module.getStatus());
            }
            moduleIds.put(module.getModuleCode(), module.getId());
        }
        if (moduleIds.isEmpty()) {
            throw new IllegalStateException("同步父任务没有可重试的非SUCCESS模块：taskId=" + taskId);
        }
        return new RetryTask(taskId, task.getEnvironment(), task.getSystemCode(), task.getLogDate(),
                task.getStatus(), moduleIds);
    }

    /**
     * 按任务下全部模块任务的当前状态汇总结局；成功口径为模块状态等于 SUCCESS。
     *
     * @param taskId 任务 ID
     * @return 模块结局汇总
     */
    @Override
    public ModuleOutcomeSummary summarizeModuleOutcomes(long taskId) {
        List<AiLogSyncModuleTaskEntity> modules = moduleMapper.selectList(
                Wrappers.<AiLogSyncModuleTaskEntity>lambdaQuery()
                        .eq(AiLogSyncModuleTaskEntity::getSyncTaskId, Long.valueOf(taskId)));
        int success = 0;
        int conflict = 0;
        for (AiLogSyncModuleTaskEntity module : modules) {
            if (SyncTaskStatus.SUCCESS.name().equals(module.getStatus())) {
                success++;
            } else if (SyncTaskStatus.CONFLICT.name().equals(module.getStatus())) {
                conflict++;
            }
        }
        return new ModuleOutcomeSummary(modules.size(), success, conflict);
    }

    /**
     * 将失败任务重置为待处理状态，供重试使用。
     *
     * @param taskId              任务 ID
     * @param expectedModuleCount 本次加载到的可重试模块数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetForRetry(long taskId, int expectedModuleCount) {
        if (expectedModuleCount <= 0) {
            throw new IllegalArgumentException("同步重试模块数量必须大于0：taskId=" + taskId);
        }
        int updated = taskMapper.update(null, Wrappers.<AiLogSyncTaskEntity>lambdaUpdate()
                .eq(AiLogSyncTaskEntity::getId, Long.valueOf(taskId))
                .in(AiLogSyncTaskEntity::getStatus, SyncTaskStatus.FAILED.name(),
                        SyncTaskStatus.PARTIAL_SUCCESS.name(), SyncTaskStatus.CONFLICT.name())
                .set(AiLogSyncTaskEntity::getStatus, SyncTaskStatus.PENDING.name())
                .set(AiLogSyncTaskEntity::getFinishTime, null)
                .set(AiLogSyncTaskEntity::getErrorMessage, null));
        if (updated != 1) {
            throw new IllegalStateException("同步父任务已被其他执行者处理或状态不再可重试：taskId=" + taskId);
        }

        AiLogSyncModuleTaskEntity module = new AiLogSyncModuleTaskEntity();
        module.setStatus(SyncTaskStatus.PENDING.name());
        int moduleUpdated = moduleMapper.update(module, Wrappers.<AiLogSyncModuleTaskEntity>lambdaUpdate()
                .eq(AiLogSyncModuleTaskEntity::getSyncTaskId, Long.valueOf(taskId))
                .in(AiLogSyncModuleTaskEntity::getStatus, SyncTaskStatus.FAILED.name(),
                        SyncTaskStatus.CONFLICT.name())
                .set(AiLogSyncModuleTaskEntity::getFinishTime, null)
                .set(AiLogSyncModuleTaskEntity::getErrorMessage, null));
        if (moduleUpdated != expectedModuleCount) {
            throw new IllegalStateException("同步可重试模块已发生并发状态变化，拒绝部分重置：taskId="
                    + taskId + ", expected=" + expectedModuleCount + ", actual=" + moduleUpdated);
        }
    }

    /**
     * 创建同步任务及其模块、文件记录。
     *
     * @param taskNo      任务号
     * @param environment 环境标识
     * @param systemCode  系统编码
     * @param logDate     日志日期
     * @param triggerType 触发类型
     * @param modules     模块规格列表
     * @return 任务注册结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskRegistration createTask(String taskNo, String environment, String systemCode,
            LocalDate logDate, String triggerType, List<ModuleSpec> modules) {
        rejectDuplicateBusinessDate(environment, systemCode, logDate);
        AiLogSyncTaskEntity task = new AiLogSyncTaskEntity();
        task.setTaskNo(taskNo);
        task.setEnvironment(environment);
        task.setSystemCode(systemCode);
        task.setLogDate(logDate);
        task.setTriggerType(triggerType);
        task.setStatus(SyncTaskStatus.PENDING.name());
        task.setTotalModuleCount(modules.size());
        task.setSuccessModuleCount(0);
        task.setFailedModuleCount(0);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException exception) {
            if (isBusinessDateConflict(exception)) {
                throw duplicateBusinessDate(environment, systemCode, logDate, exception);
            }
            throw exception;
        }

        Map<String, Long> moduleIds = new LinkedHashMap<>();
        for (ModuleSpec spec : modules) {
            AiLogSyncModuleTaskEntity module = new AiLogSyncModuleTaskEntity();
            module.setSyncTaskId(task.getId());
            module.setModuleConfigId(spec.getModuleConfigId());
            module.setModuleCode(spec.getModuleCode());
            module.setStatus(SyncTaskStatus.PENDING.name());
            module.setTotalFileCount(Integer.valueOf(spec.getFiles().size()));
            module.setSuccessFileCount(Integer.valueOf(0));
            module.setFailedFileCount(Integer.valueOf(0));
            moduleMapper.insert(module);
            moduleIds.put(spec.getModuleCode(), module.getId());
            insertFiles(task, module, spec.getFiles());
        }
        return new TaskRegistration(task.getId().longValue(), moduleIds);
    }

    /** 创建前提供可读错误；数据库唯一键仍负责封住并发窗口。 */
    private void rejectDuplicateBusinessDate(String environment, String systemCode,
            LocalDate logDate) {
        AiLogSyncTaskEntity existing = findByBusinessDate(environment, systemCode, logDate);
        if (existing != null) {
            throw duplicateBusinessDate(environment, systemCode, logDate, null);
        }
    }

    /** 按业务日期唯一范围查找已有父任务。 */
    private AiLogSyncTaskEntity findByBusinessDate(String environment, String systemCode,
            LocalDate logDate) {
        return taskMapper.selectByBusinessDate(environment, systemCode, logDate);
    }

    /** 构造单日期父任务重复错误。 */
    private static IllegalStateException duplicateBusinessDate(String environment,
            String systemCode, LocalDate logDate, DuplicateKeyException cause) {
        String message = "同一环境、系统和日志日期只能创建一个同步父任务；"
                + "已有任务请使用原任务重试，不支持同日新建父任务追加模块：environment="
                + environment + ", systemCode=" + systemCode + ", logDate=" + logDate;
        return cause == null ? new IllegalStateException(message)
                : new IllegalStateException(message, cause);
    }

    /** 只翻译业务日期唯一键冲突，其他唯一键异常保持原始语义。 */
    private static boolean isBusinessDateConflict(DuplicateKeyException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("uk_sync_scope")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 批量插入模块下的文件记录。
     *
     * @param task   同步任务实体
     * @param module 模块子任务实体
     * @param files  文件规格列表
     */
    private void insertFiles(AiLogSyncTaskEntity task, AiLogSyncModuleTaskEntity module,
            List<FileSpec> files) {
        for (FileSpec spec : files) {
            AiLogFileRecordEntity file = new AiLogFileRecordEntity();
            file.setSystemCode(task.getSystemCode());
            file.setEnvironment(task.getEnvironment());
            file.setModuleCode(module.getModuleCode());
            file.setLogDate(task.getLogDate());
            file.setFileType(spec.getChannel().name());
            file.setRemotePath(spec.getRemotePath());
            file.setLocalPath(spec.getLocalPath());
            file.setFileName(spec.getFileName());
            file.setFileSize(Long.valueOf(0L));
            file.setSyncTaskId(task.getId());
            file.setSyncModuleTaskId(module.getId());
            file.setSyncStatus(LogFileSyncStatus.PENDING.name());
            file.setParseStatus(LogFileParseStatus.WAITING.name());
            fileMapper.insert(file);
        }
    }

    /**
     * 将任务标记为执行中并记录开始、心跳时间。
     *
     * @param taskId 任务 ID
     */
    @Override
    public void markTaskRunning(long taskId) {
        AiLogSyncTaskEntity value = new AiLogSyncTaskEntity();
        value.setStatus(SyncTaskStatus.RUNNING.name());
        value.setStartTime(now());
        value.setHeartbeatTime(now());
        int updated = taskMapper.update(value, Wrappers.<AiLogSyncTaskEntity>lambdaUpdate()
                .eq(AiLogSyncTaskEntity::getId, Long.valueOf(taskId))
                .eq(AiLogSyncTaskEntity::getStatus, SyncTaskStatus.PENDING.name()));
        if (updated != 1) {
            throw new IllegalStateException("同步父任务进入RUNNING失败，当前状态不是PENDING：taskId=" + taskId);
        }
    }

    /**
     * 将模块子任务标记为执行中。
     *
     * @param moduleTaskId 模块任务 ID
     */
    @Override
    public void markModuleRunning(long moduleTaskId) {
        AiLogSyncModuleTaskEntity value = new AiLogSyncModuleTaskEntity();
        value.setStatus(SyncTaskStatus.RUNNING.name());
        value.setStartTime(now());
        int updated = moduleMapper.update(value, Wrappers.<AiLogSyncModuleTaskEntity>lambdaUpdate()
                .eq(AiLogSyncModuleTaskEntity::getId, Long.valueOf(moduleTaskId))
                .eq(AiLogSyncModuleTaskEntity::getStatus, SyncTaskStatus.PENDING.name()));
        if (updated != 1) {
            throw new IllegalStateException("同步模块进入RUNNING失败，当前状态不是PENDING：moduleTaskId="
                    + moduleTaskId);
        }
    }

    /**
     * 将模块下指定渠道的文件标记为同步中。
     *
     * @param moduleTaskId 模块任务 ID
     * @param channel      日志渠道
     */
    @Override
    public void markFileSyncing(long moduleTaskId, LogChannel channel) {
        AiLogFileRecordEntity value = new AiLogFileRecordEntity();
        value.setSyncStatus(LogFileSyncStatus.SYNCING.name());
        fileMapper.update(value, Wrappers.<AiLogFileRecordEntity>lambdaUpdate()
                .eq(AiLogFileRecordEntity::getSyncModuleTaskId, Long.valueOf(moduleTaskId))
                .eq(AiLogFileRecordEntity::getFileType, channel.name())
                .ne(AiLogFileRecordEntity::getSyncStatus, LogFileSyncStatus.READY.name())
                .set(AiLogFileRecordEntity::getErrorMessage, null));
    }

    /**
     * 将模块下指定渠道的文件标记为同步失败。
     *
     * @param moduleTaskId 模块任务 ID
     * @param channel      日志渠道
     * @param errorMessage 错误消息
     */
    @Override
    public void markFileFailed(long moduleTaskId, LogChannel channel, String errorMessage) {
        AiLogFileRecordEntity value = new AiLogFileRecordEntity();
        value.setSyncStatus(LogFileSyncStatus.FAILED.name());
        value.setErrorMessage(errorMessage);
        fileMapper.update(value, Wrappers.<AiLogFileRecordEntity>lambdaUpdate()
                .eq(AiLogFileRecordEntity::getSyncModuleTaskId, Long.valueOf(moduleTaskId))
                .eq(AiLogFileRecordEntity::getFileType, channel.name()));
    }

    /**
     * 将模块下指定渠道的文件标记为就绪并记录元数据。
     *
     * @param moduleTaskId 模块任务 ID
     * @param channel      日志渠道
     * @param remotePath   远端文件路径
     * @param localPath    本地文件路径
     * @param fileName     文件名
     * @param fileSize     文件大小字节数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markFileReady(long moduleTaskId, LogChannel channel, String remotePath, String localPath,
            String fileName, long fileSize) {
        AiLogFileRecordEntity value = new AiLogFileRecordEntity();
        value.setSyncStatus(LogFileSyncStatus.READY.name());
        // WAITING 已在创建文件记录时设置；同步重试不得重置已有解析状态。
        value.setRemotePath(remotePath);
        value.setLocalPath(localPath);
        value.setFileName(fileName);
        value.setFileSize(Long.valueOf(fileSize));
        value.setReadyTime(now());
        fileMapper.update(value, Wrappers.<AiLogFileRecordEntity>lambdaUpdate()
                .eq(AiLogFileRecordEntity::getSyncModuleTaskId, Long.valueOf(moduleTaskId))
                .eq(AiLogFileRecordEntity::getFileType, channel.name()));
        AiLogFileRecordEntity file = fileMapper.selectOne(
                Wrappers.<AiLogFileRecordEntity>lambdaQuery()
                        .eq(AiLogFileRecordEntity::getSyncModuleTaskId, Long.valueOf(moduleTaskId))
                        .eq(AiLogFileRecordEntity::getFileType, channel.name()));
        if (channel == LogChannel.ERROR) {
            createAnalysisAndCleanupTasksIfAbsent(file);
        }
    }

    /**
     * ERROR ready 文件发布成功后立即建立分析任务和清理审计记录。
     * <p>
     * 本方法由 {@link #markFileReady(long, LogChannel, String, String, String, long)} 的事务调用，
     * 保证文件 READY、分析任务 WAITING 和清理任务 WAITING_ANALYSIS 同时提交或回滚。
     *
     * @param file 已发布到 ready 目录的文件记录
     */
    private void createAnalysisAndCleanupTasksIfAbsent(AiLogFileRecordEntity file) {
        if (file == null) {
            throw new IllegalStateException("Ready ERROR file record not found when creating tasks");
        }
        //创建解析任务
        AiLogAnalysisTaskEntity analysisTask = findOrCreateAnalysisTask(file);
        //清理任务的分析任务ID
        Long count = cleanupMapper.selectCount(Wrappers.<AiLogFileCleanupEntity>lambdaQuery()
                .eq(AiLogFileCleanupEntity::getFileRecordId, file.getId()));
        if (count != null && count.longValue() > 0L) {
            cleanupMapper.update(null, Wrappers.<AiLogFileCleanupEntity>lambdaUpdate()
                    .eq(AiLogFileCleanupEntity::getFileRecordId, file.getId())
                    .isNull(AiLogFileCleanupEntity::getAnalysisTaskId)
                    .set(AiLogFileCleanupEntity::getAnalysisTaskId, analysisTask.getId()));
            return;
        }
        AiLogFileCleanupEntity cleanup = new AiLogFileCleanupEntity();
        cleanup.setFileRecordId(file.getId());
        cleanup.setAnalysisTaskId(analysisTask.getId());
        cleanup.setLocalPath(file.getLocalPath());
        cleanup.setStatus(FileCleanupStatus.WAITING_ANALYSIS.name());
        cleanup.setTriggerReason(FileCleanupTriggerReason.READY_PUBLISHED.name());
        cleanup.setRetryCount(Integer.valueOf(0));
        cleanup.setManualRequired(Boolean.FALSE);
        cleanupMapper.insert(cleanup);
    }

    /** 查找或创建文件当前指纹版本对应的待分析任务。 */
    private AiLogAnalysisTaskEntity findOrCreateAnalysisTask(AiLogFileRecordEntity file) {
        AiLogAnalysisTaskEntity existing = analysisTaskMapper.selectOne(
                Wrappers.<AiLogAnalysisTaskEntity>lambdaQuery()
                        .eq(AiLogAnalysisTaskEntity::getFileRecordId, file.getId())
                        .eq(AiLogAnalysisTaskEntity::getFingerprintVersion, fingerprintVersion));
        if (existing != null) {
            return existing;
        }
        AiLogAnalysisTaskEntity task = new AiLogAnalysisTaskEntity();
        task.setTaskNo("ANL-" + UUID.randomUUID().toString().replace("-", ""));
        task.setFileRecordId(file.getId());
        task.setSyncTaskId(file.getSyncTaskId());
        task.setSyncModuleTaskId(file.getSyncModuleTaskId());
        task.setEnvironment(file.getEnvironment());
        task.setSystemCode(file.getSystemCode());
        task.setModuleCode(file.getModuleCode());
        task.setLogDate(file.getLogDate());
        task.setStatus(AnalysisTaskStatus.WAITING.name());
        task.setFingerprintVersion(fingerprintVersion);
        task.setRawEventCount(Long.valueOf(0L));
        task.setStrictErrorCount(Long.valueOf(0L));
        task.setFallbackErrorCount(Long.valueOf(0L));
        task.setRejectedEventCount(Long.valueOf(0L));
        task.setPersistedErrorCount(Long.valueOf(0L));
        task.setIssueCount(Long.valueOf(0L));
        task.setRetryCount(Integer.valueOf(0));
        analysisTaskMapper.insert(task);
        return task;
    }

    /**
     * 将模块子任务标记为成功。
     *
     * @param moduleTaskId   模块任务 ID
     * @param totalFileCount 文件总数
     */
    @Override
    public void markModuleSuccess(long moduleTaskId, int totalFileCount) {
        completeModule(moduleTaskId, SyncTaskStatus.SUCCESS, totalFileCount, 0, null);
    }

    /**
     * 将模块子任务标记为失败，成功文件数按已就绪记录统计。
     *
     * @param moduleTaskId   模块任务 ID
     * @param totalFileCount 文件总数
     * @param errorMessage   错误消息
     */
    @Override
    public void markModuleFailed(long moduleTaskId, int totalFileCount, String errorMessage) {
        Long readyCount = fileMapper.selectCount(Wrappers.<AiLogFileRecordEntity>lambdaQuery()
                .eq(AiLogFileRecordEntity::getSyncModuleTaskId, Long.valueOf(moduleTaskId))
                .eq(AiLogFileRecordEntity::getSyncStatus, LogFileSyncStatus.READY.name()));
        int success = readyCount == null ? 0 : readyCount.intValue();
        completeModule(moduleTaskId, SyncTaskStatus.FAILED, success,
                Math.max(0, totalFileCount - success), errorMessage);
    }

    /** 标记模块因同步锁冲突而跳过，不增加失败文件数。 */
    @Override
    public void markModuleConflict(long moduleTaskId, String message) {
        AiLogSyncModuleTaskEntity value = new AiLogSyncModuleTaskEntity();
        value.setId(Long.valueOf(moduleTaskId));
        value.setStatus(SyncTaskStatus.CONFLICT.name());
        value.setSuccessFileCount(Integer.valueOf(0));
        value.setFailedFileCount(Integer.valueOf(0));
        value.setFinishTime(now());
        value.setErrorMessage(message);
        moduleMapper.updateById(value);
    }

    /**
     * 完成模块子任务并写入计数、完成时间和错误消息。
     *
     * @param id     模块任务 ID
     * @param status 任务状态
     * @param success 成功文件数
     * @param failed  失败文件数
     * @param error   错误消息
     */
    private void completeModule(long id, SyncTaskStatus status, int success, int failed, String error) {
        AiLogSyncModuleTaskEntity value = new AiLogSyncModuleTaskEntity();
        value.setId(Long.valueOf(id));
        value.setStatus(status.name());
        value.setSuccessFileCount(Integer.valueOf(success));
        value.setFailedFileCount(Integer.valueOf(failed));
        value.setFinishTime(now());
        value.setErrorMessage(error);
        moduleMapper.updateById(value);
    }

    /**
     * 完成任务并写入模块计数，仅允许 RUNNING 状态任务收尾。
     *
     * @param taskId        任务 ID
     * @param status        任务终态
     * @param successCount  成功模块数
     * @param failedCount   失败模块数
     * @param errorMessage  错误消息
     * @throws IllegalStateException 任务不在 RUNNING 状态时抛出
     */
    @Override
    public void completeTask(long taskId, SyncTaskStatus status, int successCount, int failedCount,
            String errorMessage) {
        AiLogSyncTaskEntity value = new AiLogSyncTaskEntity();
        value.setId(Long.valueOf(taskId));
        value.setStatus(status.name());
        value.setSuccessModuleCount(Integer.valueOf(successCount));
        value.setFailedModuleCount(Integer.valueOf(failedCount));
        value.setFinishTime(now());
        value.setHeartbeatTime(now());
        value.setErrorMessage(errorMessage);
        int updated = taskMapper.update(value, Wrappers.<AiLogSyncTaskEntity>lambdaUpdate()
                .eq(AiLogSyncTaskEntity::getId, Long.valueOf(taskId))
                .eq(AiLogSyncTaskEntity::getStatus, SyncTaskStatus.RUNNING.name()));
        if (updated != 1) {
            throw new IllegalStateException("Sync task completion rejected because task is not RUNNING: " + taskId);
        }
    }

    /**
     * 返回当前时间。
     *
     * @return 当前时间
     */
    private static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
