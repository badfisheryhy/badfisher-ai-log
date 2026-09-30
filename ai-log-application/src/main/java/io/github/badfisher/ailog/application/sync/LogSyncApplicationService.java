package io.github.badfisher.ailog.application.sync;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;

import io.github.badfisher.ailog.application.command.BatchLogSyncCommand;
import io.github.badfisher.ailog.application.command.LogSyncCommand;
import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.application.lock.DistributedLock;
import io.github.badfisher.ailog.application.lock.DistributedLockManager;
import io.github.badfisher.ailog.application.plan.LogSyncPlanBuilder;
import io.github.badfisher.ailog.application.tool.SourceCodeSyncTool;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.domain.config.LogModuleConfigRepository;
import io.github.badfisher.ailog.domain.sync.LogChannel;
import io.github.badfisher.ailog.domain.sync.LogSyncItem;
import io.github.badfisher.ailog.domain.sync.LogSyncPlan;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.FileSpec;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.ModuleOutcomeSummary;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.ModuleSpec;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.TaskRegistration;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.RetryTask;
import io.github.badfisher.ailog.domain.sync.SyncTaskStatus;
import io.github.badfisher.ailog.ingestion.sync.LogSyncException;
import io.github.badfisher.ailog.ingestion.sync.LogSyncFileResult;
import io.github.badfisher.ailog.ingestion.sync.LogSyncProgressListener;
import io.github.badfisher.ailog.ingestion.sync.ModuleLogSyncResult;
import io.github.badfisher.ailog.ingestion.sync.PlannedLogSyncService;
import io.github.badfisher.ailog.ingestion.sync.SyncErrorCode;

/** 数据库动态配置驱动的单模块与有界并发批量同步应用服务。 */
@Slf4j
public final class LogSyncApplicationService {

    /** 任务日期格式化，形如 20260819。 */
    private static final DateTimeFormatter TASK_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    /** 错误消息最大保留长度。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;
    /** 任务号随机段长度。 */
    private static final int TASK_NO_RANDOM_LENGTH = 12;
    /** 任务号前缀。 */
    private static final String TASK_NO_PREFIX = "SYNC-";
    /** 锁键前缀。 */
    private static final String LOCK_KEY_PREFIX = "ai-log:sync:";

    /** 模块配置仓储。 */
    private final LogModuleConfigRepository repository;
    /** 同步任务仓储。 */
    private final LogSyncTaskRepository taskRepository;
    /** 同步计划构建器。 */
    private final LogSyncPlanBuilder planBuilder;
    /** 计划同步服务。 */
    private final PlannedLogSyncService syncService;
    /** 分布式锁管理器。 */
    private final DistributedLockManager lockManager;
    /** 模块级并发同步线程池。 */
    private final Executor moduleSyncExecutor;
    /** 日志业务时区。 */
    private final ZoneId businessZone;
    /** 日志传输前执行的源码同步端口。 */
    private final SourceCodeSyncTool sourceCodeSync;
    /** 日志同步总开关。 */
    private final LogSyncProperties syncProperties;

    /**
     * 构造同步应用服务。
     *
     * @param configRepository        模块配置仓储
     * @param syncTaskRepository      同步任务仓储
     * @param builder                 同步计划构建器
     * @param service                 计划同步服务
     * @param distributedLockManager  分布式锁管理器
     * @param executor                模块级并发同步线程池
     * @param zone                    日志业务时区
     * @param codeSync                源码同步端口
     * @param properties              日志同步配置
     * @throws IllegalArgumentException 仓储或线程池为空时抛出
     */
    public LogSyncApplicationService(LogModuleConfigRepository configRepository,
            LogSyncTaskRepository syncTaskRepository,
            LogSyncPlanBuilder builder, PlannedLogSyncService service,
            DistributedLockManager distributedLockManager, Executor executor, ZoneId zone,
            SourceCodeSyncTool codeSync, LogSyncProperties properties) {
        if (configRepository == null || syncTaskRepository == null || distributedLockManager == null
                || executor == null || zone == null || codeSync == null || properties == null) {
            throw new IllegalArgumentException(
                    "repositories, distributedLockManager, executor, zone, codeSync "
                            + "and properties must not be null");
        }
        repository = configRepository;
        taskRepository = syncTaskRepository;
        planBuilder = builder;
        syncService = service;
        lockManager = distributedLockManager;
        moduleSyncExecutor = executor;
        businessZone = zone;
        sourceCodeSync = codeSync;
        syncProperties = properties;
    }

    /**
     * 同步单个模块的日志。
     *
     * @param command 单模块同步命令
     * @return 模块同步结果
     * @throws LogSyncException 配置不存在或同步中失败时抛出
     */
    public ModuleLogSyncResult syncModule(LogSyncCommand command) {
        ensureSyncEnabled();
        validateForce(command.isForce());
        validateDate(command.getAnalysisDate());
        Optional<LogModuleConfig> found = repository.findEnabledModule(command.getEnvironment(),
                command.getSystemCode(), command.getModuleCode());
        if (!found.isPresent()) {
            throw new LogSyncException(SyncErrorCode.CONFIG_NOT_FOUND,
                    "Enabled module config not found: " + command.getEnvironment() + "/"
                            + command.getSystemCode() + "/" + command.getModuleCode());
        }
        LogSyncPlan plan = planBuilder.build(found.get(), command.getAnalysisDate());
        TaskRegistration registration = register(command.getEnvironment(), command.getSystemCode(),
                command.getAnalysisDate(), command.getTriggerType().name(), found.get(), plan);
        long taskId = registration.getTaskId();
        long moduleTaskId = registration.requiredModuleTaskId(plan.getModuleCode());
        taskRepository.markTaskRunning(taskId);
        try {
            ModuleLogSyncResult result = executePlan(plan, found.get(), moduleTaskId, command.isForce());
            taskRepository.completeTask(taskId, SyncTaskStatus.SUCCESS, 1, 0, null);
            return result;
        } catch (Throwable ex) {
            if (isLockConflict(ex)) {
                taskRepository.completeTask(taskId, SyncTaskStatus.CONFLICT, 0, 0, message(ex));
            } else {
                taskRepository.completeTask(taskId, SyncTaskStatus.FAILED, 0, 1, message(ex));
            }
            throw propagate(ex);
        }
    }

    /** 手工入口同步单模块；生产环境在任何查询或写入前拒绝。 */
    public ModuleLogSyncResult syncModuleManually(LogSyncCommand command) {
        ensureSyncEnabled();
        validateManualEnvironment(command.getEnvironment());
        return syncModule(command);
    }

    /**
     * 批量同步系统内全部启用模块，模块间有界并发、互不阻断。
     *
     * @param command 批量同步命令
     * @return 批量同步汇总
     */
    public BatchLogSyncResult syncEnabledModules(final BatchLogSyncCommand command) {
        ensureSyncEnabled();
        validateForce(command.isForce());
        validateDate(command.getAnalysisDate());
        Instant startedAt = Instant.now();
        List<LogModuleConfig> snapshots = repository.findEnabledModules(
                command.getEnvironment(), command.getSystemCode());
        if (snapshots.isEmpty()) {
            throw new LogSyncException(SyncErrorCode.CONFIG_NOT_FOUND,
                    "该环境和系统没有启用的日志模块，未创建同步任务：environment="
                            + command.getEnvironment() + ", systemCode=" + command.getSystemCode());
        }
        final List<LogSyncPlan> plans = new ArrayList<>();
        for (LogModuleConfig snapshot : snapshots) {
            plans.add(planBuilder.build(snapshot, command.getAnalysisDate()));
        }
        final TaskRegistration registration = register(command.getEnvironment(), command.getSystemCode(),
                command.getAnalysisDate(), command.getTriggerType().name(), snapshots, plans);
        taskRepository.markTaskRunning(registration.getTaskId());
        try {
            List<BatchModuleSyncResult> results = executeConcurrently(plans, snapshots, command.isForce(),
                    plan -> registration.requiredModuleTaskId(plan.getModuleCode()));
            BatchLogSyncResult batchResult = new BatchLogSyncResult(
                    results, Duration.between(startedAt, Instant.now()));
            int success = (int) batchResult.getSuccessCount();
            int failed = (int) batchResult.getFailureCount();
            int conflict = (int) batchResult.getConflictCount();
            SyncTaskStatus status = taskStatus(success, failed, conflict);
            taskRepository.completeTask(registration.getTaskId(), status, success, failed,
                    resultMessage(failed, conflict));
            log.info("event=batch_log_sync_completed 批量日志同步完成：environment={}, systemCode={}, "
                            + "date={}, totalModules={}, success={}, failed={}, conflict={}, durationMs={}",
                    command.getEnvironment(), command.getSystemCode(), command.getAnalysisDate(),
                    results.size(), success, failed, conflict,
                    batchResult.getDuration().toMillis());
            return batchResult;
        } catch (Throwable ex) {
            completeTaskBestEffort(registration.getTaskId(), plans.size(), ex);
            throw propagate(ex);
        }
    }

    /** 手工入口同步系统模块；受控调度不经过手工环境限制。 */
    public BatchLogSyncResult syncEnabledModulesManually(BatchLogSyncCommand command) {
        ensureSyncEnabled();
        validateManualEnvironment(command.getEnvironment());
        return syncEnabledModules(command);
    }

    /**
     * 在原任务聚合上重跑失败任务。
     * <p>只重跑<b>FAILED/CONFLICT</b> 模块，PENDING/RUNNING 快照拒绝重试；
     * 已成功模块的 ready 文件与分析结果保持原样；
     * 执行完成后按任务下<b>全部</b>模块的当前状态重新统计父任务，历史成功模块计入成功数。</p>
     */
    public BatchLogSyncResult retry(long taskId, boolean force) {
        ensureSyncEnabled();
        validateForce(force);
        validateRetryTaskId(taskId);
        RetryTask retry = taskRepository.loadRetryTask(taskId);
        return retry(taskId, retry, force);
    }

    /**
     * 受控调度通过显式父任务 ID 发起重试。
     *
     * <p>环境和系统必须与原任务完全一致；普通按日期调度仍走新建入口，不能自动转成重试。</p>
     */
    public BatchLogSyncResult retryFromScheduler(long taskId, String environment,
            String systemCode, boolean force) {
        ensureSyncEnabled();
        validateForce(force);
        validateRetryTaskId(taskId);
        RetryTask retry = taskRepository.loadRetryTask(taskId);
        validateRetryScope(retry, environment, systemCode);
        return retry(taskId, retry, force);
    }

    /** 手工重试先从原任务读取环境，生产任务在重置前拒绝。 */
    public BatchLogSyncResult retryManually(long taskId, boolean force) {
        ensureSyncEnabled();
        validateForce(force);
        validateRetryTaskId(taskId);
        RetryTask retry = taskRepository.loadRetryTask(taskId);
        validateManualEnvironment(retry.getEnvironment());
        return retry(taskId, retry, force);
    }

    /** 使用已经读取并校验的原任务执行重试，避免手工入口重复查询旧快照。 */
    private BatchLogSyncResult retry(long taskId, RetryTask retry, boolean force) {
        List<LogModuleConfig> configs = repository.findEnabledModules(
                retry.getEnvironment(), retry.getSystemCode());
        final Map<String, Long> moduleIds = retry.getModuleTaskIds();
        List<LogModuleConfig> retryConfigs = new ArrayList<LogModuleConfig>();
        List<String> missingModules = new ArrayList<String>(moduleIds.keySet());
        for (LogModuleConfig config : configs) {
            if (missingModules.remove(config.getModuleCode())) {
                retryConfigs.add(config);
            }
        }
        if (!missingModules.isEmpty()) {
            throw new IllegalStateException("同步重试缺少原任务所需的启用模块配置：taskId="
                    + taskId + ", missingModules=" + missingModules);
        }
        final List<LogSyncPlan> plans = new ArrayList<LogSyncPlan>();
        for (LogModuleConfig config : retryConfigs) {
            plans.add(planBuilder.build(config, retry.getLogDate()));
        }
        taskRepository.resetForRetry(taskId, moduleIds.size());
        taskRepository.markTaskRunning(taskId);
        return executeRegisteredBatch(taskId, moduleIds, plans, retryConfigs, force, Instant.now());
    }

    /** 在创建或重置数据库任务前拒绝首期不支持的强制覆盖。 */
    private static void validateForce(boolean force) {
        if (force) {
            throw new LogSyncException(SyncErrorCode.FORCE_DISABLED,
                    "首期禁止 force=true，不能强制覆盖已有日志文件");
        }
    }

    /** 在任何同步任务登记或外部调用之前拒绝已主动关闭的日志同步。 */
    private void ensureSyncEnabled() {
        if (!syncProperties.isEnabled()) {
            throw new LogSyncException(SyncErrorCode.SYNC_DISABLED,
                    "日志同步已关闭，当前使用服务器本地日志直接分析模式");
        }
    }

    /** 重试必须明确引用已持久化的正数父任务 ID。 */
    private static void validateRetryTaskId(long taskId) {
        if (taskId <= 0L) {
            throw new IllegalArgumentException("同步重试taskId必须为正整数");
        }
    }

    /** 调度重试参数必须与原父任务的责任范围一致。 */
    private static void validateRetryScope(RetryTask retry, String environment,
            String systemCode) {
        if (!retry.getEnvironment().equals(environment)) {
            throw new IllegalArgumentException("调度同步重试environment与原任务不一致：taskId="
                    + retry.getTaskId() + ", expected=" + retry.getEnvironment()
                    + ", actual=" + environment);
        }
        if (!retry.getSystemCode().equals(systemCode)) {
            throw new IllegalArgumentException("调度同步重试systemCode与原任务不一致：taskId="
                    + retry.getTaskId() + ", expected=" + retry.getSystemCode()
                    + ", actual=" + systemCode);
        }
    }

    /** 按部署策略限制人工同步，调度触发不受此策略影响。 */
    private void validateManualEnvironment(String environment) {
        List<String> denied = syncProperties.getManualSyncDeniedEnvironments();
        if (denied == null) {
            throw new IllegalStateException("人工同步环境策略不能为空");
        }
        for (String configured : denied) {
            if (configured != null && configured.trim().equalsIgnoreCase(environment)) {
                throw new LogSyncException(SyncErrorCode.MANUAL_SYNC_DISABLED,
                        "当前环境禁止通过手工同步API执行同步或重试，请使用受控调度");
            }
        }
    }

    /**
     * 在已注册任务聚合上并发执行同步并汇总结果。
     *
     * <p>【可调整点/语义】父任务统计按任务下<b>全部</b>模块的当前状态重新汇总
     * （{@code summarizeModuleOutcomes}），而不是本次执行批次的结果：重跑模块可能成功、
     * 也可能再次失败，历史成功模块始终计入成功数；返回值 {@code BatchLogSyncResult}
     * 仍只描述本次实际执行的重跑模块。</p>
     *
     * @param taskId    任务 ID
     * @param moduleIds 模块编码到模块任务 ID 的映射
     * @param plans     同步计划列表
     * @param configs   与同步计划同序的模块配置快照
     * @param force     是否强制覆盖
     * @param startedAt 开始时间
     * @return 批量同步汇总
     */
    private BatchLogSyncResult executeRegisteredBatch(final long taskId, final Map<String, Long> moduleIds,
            final List<LogSyncPlan> plans, final List<LogModuleConfig> configs,
            final boolean force, Instant startedAt) {
        try {
            List<BatchModuleSyncResult> results = executeConcurrently(plans, configs, force,
                    plan -> moduleIds.get(plan.getModuleCode()));
            BatchLogSyncResult result = new BatchLogSyncResult(results,
                    Duration.between(startedAt, Instant.now()));
            ModuleOutcomeSummary summary = taskRepository.summarizeModuleOutcomes(taskId);
            int success = summary.getSuccessModules();
            int failed = summary.getFailedModules();
            int conflict = summary.getConflictModules();
            taskRepository.completeTask(taskId, taskStatus(success, failed, conflict),
                    success, failed, resultMessage(failed, conflict));
            return result;
        } catch (Throwable ex) {
            completeTaskBestEffort(taskId, plans.size(), ex);
            throw propagate(ex);
        }
    }

    /**
     * 在模块级线程池上有界并发执行同步计划并汇总结果，模块间互不阻断。
     *
     * @param plans                同步计划列表
     * @param configs              与同步计划同序的模块配置快照
     * @param force                是否强制覆盖
     * @param moduleTaskIdProvider 同步计划到模块任务 ID 的解析器
     * @return 模块同步结果列表
     */
    private List<BatchModuleSyncResult> executeConcurrently(List<LogSyncPlan> plans,
            List<LogModuleConfig> configs, boolean force,
            Function<LogSyncPlan, Long> moduleTaskIdProvider) {
        List<CompletableFuture<BatchModuleSyncResult>> futures = new ArrayList<>();
        for (int index = 0; index < plans.size(); index++) {
            LogSyncPlan plan = plans.get(index);
            LogModuleConfig config = configs.get(index);
            try {
                futures.add(CompletableFuture.supplyAsync(
                        () -> syncModulePlan(plan, config, force, moduleTaskIdProvider), moduleSyncExecutor));
            } catch (RuntimeException ex) {
                futures.add(CompletableFuture.completedFuture(
                        failBeforeExecution(plan, moduleTaskIdProvider, ex)));
            }
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<BatchModuleSyncResult> results = new ArrayList<>();
        for (CompletableFuture<BatchModuleSyncResult> future : futures) {
            results.add(future.join());
        }
        return results;
    }

    /**
     * 执行单个模块同步计划并转换为结果；失败以失败结果返回，不阻断其他模块。
     *
     * @param plan                同步计划
     * @param config              本次模块配置快照
     * @param force               是否强制覆盖
     * @param moduleTaskIdProvider 同步计划到模块任务 ID 的解析器
     * @return 模块同步结果
     */
    private BatchModuleSyncResult syncModulePlan(LogSyncPlan plan, LogModuleConfig config, boolean force,
            Function<LogSyncPlan, Long> moduleTaskIdProvider) {
        try {
            executePlan(plan, config, moduleTaskIdProvider.apply(plan).longValue(), force);
            return new BatchModuleSyncResult(plan.getModuleCode(), true, null, "SUCCESS");
        } catch (LogSyncException ex) {
            if (ex.getErrorCode() == SyncErrorCode.ALREADY_RUNNING) {
                return new BatchModuleSyncResult(plan.getModuleCode(), false, true,
                        ex.getErrorCode().name(), ex.getMessage());
            }
            return new BatchModuleSyncResult(plan.getModuleCode(), false,
                    ex.getErrorCode().name(), ex.getMessage());
        } catch (Throwable ex) {
            return new BatchModuleSyncResult(plan.getModuleCode(), false,
                    SyncErrorCode.RSYNC_FAILED.name(), message(ex));
        }
    }

    /** 线程池拒绝等导致模块未进入异步执行时，直接记录模块失败。 */
    private BatchModuleSyncResult failBeforeExecution(LogSyncPlan plan,
            Function<LogSyncPlan, Long> moduleTaskIdProvider, RuntimeException error) {
        Long moduleTaskId = moduleTaskIdProvider.apply(plan);
        if (moduleTaskId != null) {
            taskRepository.markModuleFailed(moduleTaskId.longValue(), plan.getItems().size(), message(error));
        }
        return new BatchModuleSyncResult(plan.getModuleCode(), false,
                SyncErrorCode.RSYNC_FAILED.name(), message(error));
    }

    /**
     * 在分布式锁保护下执行单个同步计划并维护模块任务状态。
     *
     * @param plan         同步计划
     * @param config       与日志计划一致的模块配置快照
     * @param moduleTaskId 模块任务 ID
     * @param force        是否强制覆盖
     * @return 模块同步结果
     * @throws LogSyncException 已有任务在运行或同步失败时抛出
     */
    private ModuleLogSyncResult executePlan(final LogSyncPlan plan, LogModuleConfig config,
            final long moduleTaskId, boolean force) {
        String key = LOCK_KEY_PREFIX + plan.getEnvironment() + ":" + plan.getSystemCode() + ":"
                + plan.getModuleCode() + ":" + plan.getAnalysisDate();
        DistributedLock lock = lockManager.getLock(key);
        if (!lock.tryLock()) {
            String error = "Synchronization is already running: " + key;
            taskRepository.markModuleConflict(moduleTaskId, error);
            throw new LogSyncException(SyncErrorCode.ALREADY_RUNNING, error);
        }
        boolean moduleStarted = false;
        try {
            taskRepository.markModuleRunning(moduleTaskId);
            moduleStarted = true;
            prepareSourceCode(config);
            ModuleLogSyncResult result = syncService.sync(plan, force, new LogSyncProgressListener() {
                @Override
                public void syncing(LogChannel channel) {
                    taskRepository.markFileSyncing(moduleTaskId, channel);
                }

                @Override
                public void ready(LogSyncFileResult result) {
                    taskRepository.markFileReady(moduleTaskId, result.getChannel(), result.getRemotePath(),
                            result.getReadyFile().toString(), result.getReadyFile().getFileName().toString(),
                            result.getFileSize());
                }

                @Override
                public void failed(LogChannel channel, String error) {
                    taskRepository.markFileFailed(moduleTaskId, channel, error);
                }
            });
            taskRepository.markModuleSuccess(moduleTaskId, result.getFiles().size());
            return result;
        } catch (Throwable ex) {
            if (moduleStarted) {
                taskRepository.markModuleFailed(moduleTaskId, plan.getItems().size(), message(ex));
            }
            throw propagate(ex);
        } finally {
            lock.unlock();
        }
    }

    /** 源码先于日志传输完成；失败进入原模块任务失败与重试链，不发布本次新日志。 */
    private void prepareSourceCode(LogModuleConfig config) {
        if (!config.isCodeSyncEnabled()) {
            return;
        }
        try {
            sourceCodeSync.syncLatest(config);
        } catch (RuntimeException exception) {
            throw new LogSyncException(SyncErrorCode.SOURCE_CODE_SYNC_FAILED,
                    "Git源码同步失败：" + message(exception), exception);
        }
    }

    /**
     * 注册单模块同步任务。
     *
     * @param environment 环境标识
     * @param systemCode  系统编码
     * @param date        分析日期
     * @param triggerType 触发类型
     * @param config      模块配置
     * @param plan        同步计划
     * @return 任务注册结果
     */
    private TaskRegistration register(String environment, String systemCode, LocalDate date,
            String triggerType, LogModuleConfig config, LogSyncPlan plan) {
        return register(environment, systemCode, date, triggerType,
                Collections.singletonList(config), Collections.singletonList(plan));
    }

    /**
     * 注册批量同步任务。
     *
     * @param environment 环境标识
     * @param systemCode  系统编码
     * @param date        分析日期
     * @param triggerType 触发类型
     * @param configs     模块配置列表
     * @param plans       同步计划列表
     * @return 任务注册结果
     */
    private TaskRegistration register(String environment, String systemCode, LocalDate date,
            String triggerType, List<LogModuleConfig> configs, List<LogSyncPlan> plans) {
        List<ModuleSpec> modules = new ArrayList<>();
        for (int index = 0; index < plans.size(); index++) {
            LogSyncPlan plan = plans.get(index);
            List<FileSpec> files = new ArrayList<>();
            for (LogSyncItem item : plan.getItems()) {
                String fileName = item.getCandidateFiles().get(0);
                files.add(new FileSpec(item.getChannel(), item.getRemoteDirectory() + "/" + fileName,
                        item.getReadyDirectory().resolve(fileName).toString(), fileName));
            }
            modules.add(new ModuleSpec(configs.get(index).getId(), plan.getModuleCode(), files));
        }
        String taskNo = TASK_NO_PREFIX + TASK_DATE.format(date) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, TASK_NO_RANDOM_LENGTH).toUpperCase();
        return taskRepository.createTask(taskNo, environment, systemCode, date, triggerType, modules);
    }

    /**
     * 提取异常消息并截断到上限。
     *
     * @param error 异常
     * @return 截断后的消息
     */
    private static String message(Throwable error) {
        String value = error.getMessage();
        if (value == null) {
            value = error.getClass().getSimpleName();
        }
        return value.length() <= MAX_ERROR_MESSAGE_LENGTH ? value : value.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    /**
     * 校验分析日期必须为已完成的过去日期。
     *
     * @param date 分析日期
     * @throws IllegalArgumentException 日期为空或非过去日期时抛出
     */
    private void validateDate(LocalDate date) {
        if (date == null || !date.isBefore(LocalDate.now(businessZone))) {
            throw new IllegalArgumentException("只能同步已完成的历史日志日期");
        }
    }

    private static boolean isLockConflict(Throwable error) {
        return error instanceof LogSyncException
                && ((LogSyncException) error).getErrorCode() == SyncErrorCode.ALREADY_RUNNING;
    }

    private static SyncTaskStatus taskStatus(int success, int failed, int conflict) {
        if (failed > 0) {
            return success == 0 ? SyncTaskStatus.FAILED : SyncTaskStatus.PARTIAL_SUCCESS;
        }
        if (conflict > 0) {
            return success == 0 ? SyncTaskStatus.CONFLICT : SyncTaskStatus.PARTIAL_SUCCESS;
        }
        return SyncTaskStatus.SUCCESS;
    }

    private static String resultMessage(int failed, int conflict) {
        if (failed > 0) {
            return "一个或多个模块同步失败";
        }
        return conflict > 0 ? "一个或多个模块同步因锁冲突被跳过" : null;
    }

    /** 批量编排未能正常汇总时，尽最大努力终结父任务。 */
    private void completeTaskBestEffort(long taskId, int failedModules, Throwable original) {
        try {
            taskRepository.completeTask(taskId, SyncTaskStatus.FAILED, 0, failedModules,
                    message(original));
        } catch (RuntimeException completionError) {
            log.error("event=log_sync_task_failure_completion_failed 同步任务失败状态回写异常：taskId={}",
                    taskId, completionError);
        }
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException) {
            return (RuntimeException) error;
        }
        if (error instanceof Error) {
            throw (Error) error;
        }
        return new IllegalStateException(error);
    }
}
