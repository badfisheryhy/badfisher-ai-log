package io.github.badfisher.ailog.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import io.github.badfisher.ailog.application.command.BatchLogSyncCommand;
import io.github.badfisher.ailog.application.command.LogSyncCommand;
import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.application.lock.DistributedLock;
import io.github.badfisher.ailog.application.lock.DistributedLockManager;
import io.github.badfisher.ailog.application.path.LocalLogPathResolver;
import io.github.badfisher.ailog.application.plan.LogModuleConfigValidator;
import io.github.badfisher.ailog.application.plan.LogSyncPlanBuilder;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.domain.config.LogModuleConfigRepository;
import io.github.badfisher.ailog.ingestion.sync.CommandExecutor;
import io.github.badfisher.ailog.ingestion.sync.CommandResult;
import io.github.badfisher.ailog.ingestion.sync.LogSyncException;
import io.github.badfisher.ailog.ingestion.sync.PlannedLogSyncService;
import io.github.badfisher.ailog.ingestion.sync.ShellRemoteFileResolver;
import io.github.badfisher.ailog.ingestion.sync.ShellScriptCommandBuilder;
import io.github.badfisher.ailog.ingestion.sync.SyncVerifier;
import io.github.badfisher.ailog.ingestion.sync.SyncErrorCode;
import io.github.badfisher.ailog.domain.sync.LogChannel;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository;
import io.github.badfisher.ailog.domain.sync.SyncTaskStatus;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.ModuleOutcomeSummary;

class LogSyncApplicationServiceTest {
    @TempDir Path root;

    /** 测试创建的线程池，测试结束后统一关闭。 */
    private final List<ExecutorService> createdPools = new ArrayList<>();

    @AfterEach
    void shutdownPools() {
        for (ExecutorService pool : createdPools) {
            pool.shutdownNow();
        }
    }

    @Test
    void newTaskReadsChangedConfigWhileExistingTaskUsesItsSnapshot() {
        MutableRepository repository = new MutableRepository(config("sample-service", "/remote/old", 10));
        RecordingExecutor executor = new RecordingExecutor();
        LogSyncApplicationService service = service(repository, executor);
        LocalDate date = LocalDate.now().minusDays(1L);

        service.syncModule(new LogSyncCommand("prod", "demo", "sample-service", date, false));
        repository.modules = Arrays.asList(config("sample-service", "/remote/new", 10));
        service.syncModule(new LogSyncCommand("prod", "demo", "sample-service", date.minusDays(1L), false));

        assertThat(executor.checkedRemoteFiles).anyMatch(path -> path.startsWith("/remote/old/"));
        assertThat(executor.checkedRemoteFiles).anyMatch(path -> path.startsWith("/remote/new/"));
    }

    @Test
    void disabledSyncRejectsEveryPublicEntryBeforeTaskMutation() {
        MutableRepository repository = new MutableRepository(config("sample-service", "/sample-service", 10));
        RecordingExecutor executor = new RecordingExecutor();
        LogSyncTaskRepository tasks = org.mockito.Mockito.mock(LogSyncTaskRepository.class);
        LogSyncApplicationService service = service(repository, executor,
                new TestDistributedLockManager(), tasks, false);
        LocalDate date = LocalDate.now().minusDays(1L);

        assertSyncDisabled(() -> service.syncModule(
                new LogSyncCommand("prod", "demo", "sample-service", date, false)));
        assertSyncDisabled(() -> service.syncModuleManually(
                new LogSyncCommand("dev", "demo", "sample-service", date, false)));
        assertSyncDisabled(() -> service.syncEnabledModules(
                new BatchLogSyncCommand("prod", "demo", date, false)));
        assertSyncDisabled(() -> service.syncEnabledModulesManually(
                new BatchLogSyncCommand("dev", "demo", date, false)));
        assertSyncDisabled(() -> service.retry(1L, false));
        assertSyncDisabled(() -> service.retryFromScheduler(1L, "prod", "demo", false));
        assertSyncDisabled(() -> service.retryManually(1L, false));

        org.mockito.Mockito.verifyNoInteractions(tasks);
        assertThat(executor.checkedRemoteFiles).isEmpty();
    }

    @Test
    void rejectsForceAtEveryApplicationEntryBeforeAnyTaskMutation() {
        MutableRepository repository = new MutableRepository(config("sample-service", "/sample-service", 10));
        RecordingExecutor executor = new RecordingExecutor();
        LogSyncTaskRepository tasks = org.mockito.Mockito.mock(LogSyncTaskRepository.class);
        LogSyncApplicationService service = service(repository, executor,
                new DeniedDistributedLockManager(), tasks);
        LocalDate date = LocalDate.now().minusDays(1L);

        assertThatThrownBy(() -> service.syncModule(new LogSyncCommand("prod", "demo", "sample-service", date, true)))
                .hasMessageContaining("禁止 force=true");
        assertThatThrownBy(() -> service.syncEnabledModules(new BatchLogSyncCommand("prod", "demo", date, true)))
                .hasMessageContaining("禁止 force=true");
        assertThatThrownBy(() -> service.retry(1L, true)).hasMessageContaining("禁止 force=true");
        assertThatThrownBy(() -> service.retryFromScheduler(1L, "prod", "demo", true))
                .hasMessageContaining("禁止 force=true");
        org.mockito.Mockito.verifyNoInteractions(tasks);
        assertThat(executor.checkedRemoteFiles).isEmpty();
    }

    @Test
    void manualSyncRejectsProductionEnvironmentBeforeTaskMutation() {
        MutableRepository repository = new MutableRepository(config("sample-service", "/sample-service", 10));
        LogSyncTaskRepository tasks = org.mockito.Mockito.mock(LogSyncTaskRepository.class);
        LogSyncApplicationService service = service(repository, new RecordingExecutor(),
                new TestDistributedLockManager(), tasks);
        LocalDate date = LocalDate.now().minusDays(1L);

        assertThatThrownBy(() -> service.syncModuleManually(
                new LogSyncCommand("prod", "demo", "sample-service", date, false)))
                .isInstanceOfSatisfying(LogSyncException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(
                                SyncErrorCode.MANUAL_SYNC_DISABLED));
        assertThatThrownBy(() -> service.syncEnabledModulesManually(
                new BatchLogSyncCommand("production", "demo", date, false)))
                .hasMessageContaining("当前环境禁止");

        org.mockito.Mockito.verifyNoInteractions(tasks);
    }

    @Test
    void manualRetryReadsTaskEnvironmentAndRejectsProductionBeforeReset() {
        MutableRepository repository = new MutableRepository(config("sample-service", "/sample-service", 10));
        LogSyncTaskRepository tasks = org.mockito.Mockito.mock(LogSyncTaskRepository.class);
        org.mockito.Mockito.when(tasks.loadRetryTask(7L)).thenReturn(
                new LogSyncTaskRepository.RetryTask(7L, "prod", "demo",
                        LocalDate.now().minusDays(1L), "FAILED",
                        java.util.Collections.<String, Long>emptyMap()));
        LogSyncApplicationService service = service(repository, new RecordingExecutor(),
                new TestDistributedLockManager(), tasks);

        assertThatThrownBy(() -> service.retryManually(7L, false))
                .hasMessageContaining("当前环境禁止");

        org.mockito.Mockito.verify(tasks).loadRetryTask(7L);
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).resetForRetry(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void batchKeepsModulesIsolatedAndPreservesRepositoryPriorityOrder() {
        MutableRepository repository = new MutableRepository(config("sample-service", "/sample-service", 10),
                config("asinking", "/asinking", 20));
        BatchLogSyncResult result = service(repository, new RecordingExecutor()).syncEnabledModules(
                new BatchLogSyncCommand("prod", "demo", LocalDate.now().minusDays(1L), false));
        assertThat(result.getFailureCount()).isZero();
        assertThat(result.getModules()).extracting(BatchModuleSyncResult::getModuleCode)
                .containsExactly("sample-service", "asinking");
        assertThat(root.resolve("prod/demo/sample-service")).exists();
        assertThat(root.resolve("prod/demo/asinking")).exists();
    }

    @Test
    void rejectsSyncWhenDistributedLockIsAlreadyHeld() {
        MutableRepository repository = new MutableRepository(config("sample-service", "/sample-service", 10));
        RecordingExecutor executor = new RecordingExecutor();
        LogSyncApplicationService service = service(repository, executor, new DeniedDistributedLockManager());

        assertThatThrownBy(() -> service.syncModule(new LogSyncCommand("prod", "demo", "sample-service",
                LocalDate.now().minusDays(1L), false)))
                .isInstanceOfSatisfying(LogSyncException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(SyncErrorCode.ALREADY_RUNNING));
        assertThat(executor.checkedRemoteFiles).isEmpty();
    }

    @Test
    void batchLockConflictIsNotRecordedAsFailure() {
        MutableRepository repository = new MutableRepository(config("sample-service", "/sample-service", 10));
        StatefulTaskRepository tasks = new StatefulTaskRepository();
        LogSyncApplicationService service = service(repository, new RecordingExecutor(),
                new DeniedDistributedLockManager(), tasks);

        BatchLogSyncResult result = service.syncEnabledModules(new BatchLogSyncCommand(
                "prod", "demo", LocalDate.now().minusDays(1L), false));

        assertThat(result.getFailureCount()).isZero();
        assertThat(result.getConflictCount()).isEqualTo(1L);
        assertThat(tasks.moduleStatus("sample-service")).isEqualTo(SyncTaskStatus.CONFLICT);
        assertThat(tasks.completedStatus).isEqualTo(SyncTaskStatus.CONFLICT);
        assertThat(tasks.completedFailed).isEqualTo(0);
    }

    @Test
    void executorRejectionStillCompletesParentTaskAsFailed() {
        MutableRepository repository = new MutableRepository(config("sample-service", "/sample-service", 10));
        StatefulTaskRepository tasks = new StatefulTaskRepository();
        java.util.concurrent.Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("module executor rejected task");
        };
        LogSyncApplicationService service = service(repository, new RecordingExecutor(),
                new TestDistributedLockManager(), tasks, rejectingExecutor,
                ZoneId.of("Asia/Shanghai"));

        BatchLogSyncResult result = service.syncEnabledModules(new BatchLogSyncCommand(
                "prod", "demo", LocalDate.now().minusDays(1L), false));

        assertThat(result.getFailureCount()).isEqualTo(1L);
        assertThat(tasks.moduleStatus("sample-service")).isEqualTo(SyncTaskStatus.FAILED);
        assertThat(tasks.completedStatus).isEqualTo(SyncTaskStatus.FAILED);
    }

    @Test
    void validatesDateUsingConfiguredBusinessTimezone() {
        ZoneId businessZone = ZoneId.of("Pacific/Honolulu");
        MutableRepository repository = new MutableRepository(config("sample-service", "/sample-service", 10));
        LogSyncApplicationService service = service(repository, new RecordingExecutor(),
                new TestDistributedLockManager(), new RecordingTaskRepository(),
                new DirectExecutor(), businessZone);

        assertThatThrownBy(() -> service.syncEnabledModules(new BatchLogSyncCommand(
                "prod", "demo", LocalDate.now(businessZone), false)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("只能同步已完成的历史日志日期");
    }

    @Test
    void retryOnlyReRunsFailedModulesAndRecomputesParentSummary() {
        FlakyExecutor executor = new FlakyExecutor("/asinking");
        MutableRepository repository = new MutableRepository(config("sample-service", "/sample-service", 10),
                config("asinking", "/asinking", 20));
        StatefulTaskRepository tasks = new StatefulTaskRepository();
        LogSyncApplicationService service = service(repository, executor,
                new TestDistributedLockManager(), tasks);
        LocalDate date = LocalDate.now().minusDays(1L);

        service.syncEnabledModules(new BatchLogSyncCommand("prod", "demo", date, false));
        assertThat(tasks.moduleStatus("sample-service")).isEqualTo(SyncTaskStatus.SUCCESS);
        assertThat(tasks.moduleStatus("asinking")).isEqualTo(SyncTaskStatus.FAILED);
        assertThat(tasks.completedStatus).isEqualTo(SyncTaskStatus.PARTIAL_SUCCESS);
        long originalTaskId = tasks.lastTaskId();
        int createdTaskCount = tasks.createdTaskCount;

        executor.stopFailing();
        BatchLogSyncResult retried = service.retryFromScheduler(
                originalTaskId, "prod", "demo", false);

        // 需求1：只重跑 FAILED/CONFLICT 模块，已成功模块不再执行 rsync。
        assertThat(retried.getModules()).extracting(BatchModuleSyncResult::getModuleCode)
                .containsExactly("asinking");
        assertThat(executor.rsyncAttempts("sample-service")).isEqualTo(1);
        assertThat(executor.rsyncAttempts("asinking")).isEqualTo(2);
        // 需求2：父任务按全部模块的最终状态重新统计。
        assertThat(tasks.moduleStatus("asinking")).isEqualTo(SyncTaskStatus.SUCCESS);
        assertThat(tasks.completedStatus).isEqualTo(SyncTaskStatus.SUCCESS);
        assertThat(tasks.completedSuccess).isEqualTo(2);
        assertThat(tasks.completedFailed).isEqualTo(0);
        assertThat(tasks.lastTaskId()).isEqualTo(originalTaskId);
        assertThat(tasks.createdTaskCount).isEqualTo(createdTaskCount);
        assertThat(tasks.resetCount).isEqualTo(1);
        assertThat(tasks.lastResetModuleCount).isEqualTo(1);
    }

    @Test
    void scheduledRetryRejectsScopeMismatchBeforeAnyStateMutation() {
        LogSyncTaskRepository tasks = org.mockito.Mockito.mock(LogSyncTaskRepository.class);
        org.mockito.Mockito.when(tasks.loadRetryTask(7L)).thenReturn(retryTask(7L));
        LogSyncApplicationService service = service(
                new MutableRepository(config("sample-service", "/sample-service", 10)),
                new RecordingExecutor(), new TestDistributedLockManager(), tasks);

        assertThatThrownBy(() -> service.retryFromScheduler(7L, "test", "demo", false))
                .hasMessageContaining("environment与原任务不一致");
        assertThatThrownBy(() -> service.retryFromScheduler(7L, "prod", "order", false))
                .hasMessageContaining("systemCode与原任务不一致");

        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.times(2)).loadRetryTask(7L);
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).resetForRetry(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).markTaskRunning(7L);
    }

    @Test
    void scheduledRetryRejectsInvalidOrMissingTaskBeforeAnyStateMutation() {
        LogSyncTaskRepository tasks = org.mockito.Mockito.mock(LogSyncTaskRepository.class);
        org.mockito.Mockito.when(tasks.loadRetryTask(404L))
                .thenThrow(new IllegalArgumentException("同步父任务不存在：taskId=404"));
        LogSyncApplicationService service = service(
                new MutableRepository(config("sample-service", "/sample-service", 10)),
                new RecordingExecutor(), new TestDistributedLockManager(), tasks);

        assertThatThrownBy(() -> service.retryFromScheduler(0L, "prod", "demo", false))
                .hasMessageContaining("taskId必须为正整数");
        assertThatThrownBy(() -> service.retryFromScheduler(404L, "prod", "demo", false))
                .hasMessageContaining("同步父任务不存在");

        org.mockito.Mockito.verify(tasks).loadRetryTask(404L);
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).resetForRetry(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).markTaskRunning(
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void scheduledRetryRejectsRunningTaskBeforeAnyStateMutation() {
        LogSyncTaskRepository tasks = org.mockito.Mockito.mock(LogSyncTaskRepository.class);
        org.mockito.Mockito.when(tasks.loadRetryTask(8L)).thenThrow(new IllegalStateException(
                "同步父任务状态不可重试，仅FAILED、PARTIAL_SUCCESS或CONFLICT允许重试："
                        + "taskId=8, status=RUNNING"));
        LogSyncApplicationService service = service(
                new MutableRepository(config("sample-service", "/sample-service", 10)),
                new RecordingExecutor(), new TestDistributedLockManager(), tasks);

        assertThatThrownBy(() -> service.retryFromScheduler(8L, "prod", "demo", false))
                .hasMessageContaining("status=RUNNING");

        org.mockito.Mockito.verify(tasks).loadRetryTask(8L);
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).resetForRetry(
                org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).markTaskRunning(8L);
    }

    @Test
    void scheduledRetryReportsMissingModuleBeforeStateMutationOrSynchronization() {
        Map<String, Long> modules = new LinkedHashMap<String, Long>();
        modules.put("sample-service", Long.valueOf(1L));
        modules.put("asinking", Long.valueOf(2L));
        LogSyncTaskRepository tasks = org.mockito.Mockito.mock(LogSyncTaskRepository.class);
        org.mockito.Mockito.when(tasks.loadRetryTask(10L)).thenReturn(
                new LogSyncTaskRepository.RetryTask(10L, "prod", "demo",
                        LocalDate.now().minusDays(1L), "PARTIAL_SUCCESS", modules));
        RecordingExecutor executor = new RecordingExecutor();
        LogSyncApplicationService service = service(
                new MutableRepository(config("sample-service", "/sensitive/sample-service", 10)),
                executor, new TestDistributedLockManager(), tasks);

        assertThatThrownBy(() -> service.retryFromScheduler(10L, "prod", "demo", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少原任务所需的启用模块配置")
                .hasMessageContaining("taskId=10")
                .hasMessageContaining("asinking")
                .hasMessageNotContaining("/sensitive/sample-service");

        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).resetForRetry(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).markTaskRunning(
                org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).markModuleRunning(
                org.mockito.ArgumentMatchers.anyLong());
        assertThat(executor.checkedRemoteFiles).isEmpty();
    }

    @Test
    void retryDoesNotOverwriteModuleWhenPendingToRunningTransitionLosesRace() {
        LogSyncTaskRepository tasks = org.mockito.Mockito.mock(LogSyncTaskRepository.class);
        org.mockito.Mockito.when(tasks.loadRetryTask(9L)).thenReturn(retryTask(9L));
        org.mockito.Mockito.doThrow(new IllegalStateException(
                "同步模块进入RUNNING失败，当前状态不是PENDING"))
                .when(tasks).markModuleRunning(1L);
        org.mockito.Mockito.when(tasks.summarizeModuleOutcomes(9L))
                .thenReturn(new ModuleOutcomeSummary(1, 0, 0));
        LogSyncApplicationService service = service(
                new MutableRepository(config("sample-service", "/sample-service", 10)),
                new RecordingExecutor(), new TestDistributedLockManager(), tasks,
                new DirectExecutor(), ZoneId.of("Asia/Shanghai"));

        BatchLogSyncResult result = service.retryFromScheduler(9L, "prod", "demo", false);

        assertThat(result.getFailureCount()).isEqualTo(1L);
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).markModuleFailed(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void normalSameDateSchedulingDoesNotAutoConvertToRetry() {
        LogSyncTaskRepository tasks = org.mockito.Mockito.mock(LogSyncTaskRepository.class);
        org.mockito.Mockito.when(tasks.createTask(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("prod"), org.mockito.ArgumentMatchers.eq("demo"),
                org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.eq("SCHEDULER"), org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new IllegalStateException("同一环境、系统和日志日期只能创建一个同步父任务"));
        LogSyncApplicationService service = service(
                new MutableRepository(config("sample-service", "/sample-service", 10)),
                new RecordingExecutor(), new TestDistributedLockManager(), tasks);

        assertThatThrownBy(() -> service.syncEnabledModules(new BatchLogSyncCommand(
                "prod", "demo", LocalDate.now().minusDays(1L), false,
                io.github.badfisher.ailog.domain.sync.SyncTriggerType.SCHEDULER)))
                .hasMessageContaining("同一环境、系统和日志日期");

        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).loadRetryTask(
                org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).resetForRetry(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    private static LogSyncTaskRepository.RetryTask retryTask(long taskId) {
        Map<String, Long> modules = new LinkedHashMap<String, Long>();
        modules.put("sample-service", Long.valueOf(1L));
        return new LogSyncTaskRepository.RetryTask(taskId, "prod", "demo",
                LocalDate.now().minusDays(1L), "FAILED", modules);
    }

    private LogSyncApplicationService service(LogModuleConfigRepository repository, RecordingExecutor executor) {
        return service(repository, executor, new TestDistributedLockManager());
    }

    private LogSyncApplicationService service(LogModuleConfigRepository repository, RecordingExecutor executor,
            DistributedLockManager lockManager) {
        return service(repository, executor, lockManager, new RecordingTaskRepository());
    }

    private LogSyncApplicationService service(LogModuleConfigRepository repository,
            CommandExecutor executor, DistributedLockManager lockManager, LogSyncTaskRepository taskRepository) {
        return service(repository, executor, lockManager, taskRepository, true);
    }

    private LogSyncApplicationService service(LogModuleConfigRepository repository,
            CommandExecutor executor, DistributedLockManager lockManager,
            LogSyncTaskRepository taskRepository, boolean syncEnabled) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        createdPools.add(pool);
        return service(repository, executor, lockManager, taskRepository, pool,
                ZoneId.of("Asia/Shanghai"), syncEnabled);
    }

    private LogSyncApplicationService service(LogModuleConfigRepository repository,
            CommandExecutor executor, DistributedLockManager lockManager,
            LogSyncTaskRepository taskRepository, java.util.concurrent.Executor moduleExecutor,
            ZoneId businessZone) {
        return service(repository, executor, lockManager, taskRepository, moduleExecutor,
                businessZone, true);
    }

    private LogSyncApplicationService service(LogModuleConfigRepository repository,
            CommandExecutor executor, DistributedLockManager lockManager,
            LogSyncTaskRepository taskRepository, java.util.concurrent.Executor moduleExecutor,
            ZoneId businessZone, boolean syncEnabled) {
        LogSyncProperties properties = new LogSyncProperties();
        properties.setManualSyncDeniedEnvironments(Arrays.asList("prod", "production"));
        properties.setEnabled(syncEnabled);
        properties.setRootDirectory(root.toString());
        LogSyncPlanBuilder plans = new LogSyncPlanBuilder(new LocalLogPathResolver(properties),
                new LogModuleConfigValidator());
        ShellScriptCommandBuilder commands = new ShellScriptCommandBuilder("/opt/bin", "");
        PlannedLogSyncService planned = new PlannedLogSyncService(
                new ShellRemoteFileResolver(executor, commands, Duration.ofSeconds(5L)),
                executor, commands, new SyncVerifier(), Duration.ofSeconds(5L));
        return new LogSyncApplicationService(repository, taskRepository, plans, planned,
                lockManager, moduleExecutor, businessZone, module -> {
                    throw new AssertionError("日志基础测试不应启用源码同步");
                }, properties);
    }

    private static void assertSyncDisabled(ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(LogSyncException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(SyncErrorCode.SYNC_DISABLED));
    }

    private static LogModuleConfig config(String module, String remote, int priority) {
        LogModuleConfig config = new LogModuleConfig();
        config.setId(Long.valueOf(priority));
        config.setEnvironment("prod");
        config.setSystemCode("demo");
        config.setModuleCode(module);
        config.setEnabled(true);
        config.setServerHost("log.example");
        config.setServerPort(22);
        config.setSshUsername("op_read");
        config.setRemoteDirectory(remote);
        config.setLogFilePrefix("badfisher-" + module);
        config.setLocalSubDirectory(module);
        config.setSyncErrorLog(true);
        config.setSyncAllLog(false);
        config.setSyncPriority(priority);
        return config;
    }

    private static final class MutableRepository implements LogModuleConfigRepository {
        private List<LogModuleConfig> modules;
        private MutableRepository(LogModuleConfig... values) { modules = Arrays.asList(values); }
        @Override
        public List<LogModuleConfig> findCodeSyncEnabledModules(String environment) {
            return java.util.Collections.emptyList();
        }
        @Override
        public List<LogModuleConfig> findEnabledModules(String environment, String systemCode) {
            return new ArrayList<LogModuleConfig>(modules);
        }
        @Override
        public Optional<LogModuleConfig> findEnabledModule(String environment, String systemCode, String moduleCode) {
            for (LogModuleConfig module : modules) {
                if (module.getModuleCode().equals(moduleCode) && module.isEnabled()) { return Optional.of(module); }
            }
            return Optional.empty();
        }
    }

    private static final class TestDistributedLockManager implements DistributedLockManager {
        @Override
        public DistributedLock getLock(String key) {
            return new DistributedLock() {
                @Override public boolean tryLock() { return true; }
                @Override public void unlock() { }
            };
        }
    }

    private static final class DeniedDistributedLockManager implements DistributedLockManager {
        @Override
        public DistributedLock getLock(String key) {
            return new DistributedLock() {
                @Override public boolean tryLock() { return false; }
                @Override public void unlock() { }
            };
        }
    }

    /** 当前线程直接执行，便于验证编排边界。 */
    private static final class DirectExecutor implements java.util.concurrent.Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class RecordingExecutor implements CommandExecutor {
        private final List<String> checkedRemoteFiles = new ArrayList<String>();
        @Override
        public synchronized CommandResult execute(List<String> command, Duration timeout) {
            try {
                if (command.get(0).endsWith("check-remote-file.sh")) {
                    checkedRemoteFiles.add(command.get(4));
                    return new CommandResult(0, "");
                }
                String remote = command.get(4);
                Path target = java.nio.file.Paths.get(command.get(5));
                Files.createDirectories(target);
                Files.write(target.resolve(remote.substring(remote.lastIndexOf('/') + 1)),
                        "ERROR test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return new CommandResult(0, "");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    /** 可控失败的命令执行器：远程检查一律成功，rsync 对指定前缀模块在开关打开时返回失败退出码。 */
    private static final class FlakyExecutor implements CommandExecutor {
        private final Map<String, Integer> rsyncAttempts = new LinkedHashMap<String, Integer>();
        private final String failingPrefix;
        private volatile boolean failing = true;

        private FlakyExecutor(String prefix) {
            failingPrefix = prefix;
        }

        private void stopFailing() {
            failing = false;
        }

        private int rsyncAttempts(String module) {
            Integer count = rsyncAttempts.get(module);
            return count == null ? 0 : count.intValue();
        }

        @Override
        public synchronized CommandResult execute(List<String> command, Duration timeout) {
            try {
                if (command.get(0).endsWith("check-remote-file.sh")) {
                    return new CommandResult(0, "");
                }
                String remote = command.get(4);
                String module = remote.startsWith("/sample-service") ? "sample-service" : "asinking";
                Integer count = rsyncAttempts.get(module);
                rsyncAttempts.put(module, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
                if (failing && remote.startsWith(failingPrefix)) {
                    return new CommandResult(12, "mock rsync failure");
                }
                Path target = java.nio.file.Paths.get(command.get(5));
                Files.createDirectories(target);
                Files.write(target.resolve(remote.substring(remote.lastIndexOf('/') + 1)),
                        "ERROR test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return new CommandResult(0, "");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    /** 带状态的任务仓储：记录模块任务状态迁移与父任务终结口径，模拟数据库聚合语义。 */
    private static final class StatefulTaskRepository implements LogSyncTaskRepository {
        private final Map<String, Long> moduleIds = new LinkedHashMap<String, Long>();
        private final Map<String, SyncTaskStatus> moduleStatus =
                new LinkedHashMap<String, SyncTaskStatus>();
        private String environment;
        private String systemCode;
        private LocalDate logDate;
        private long currentTaskId;
        private SyncTaskStatus taskStatus;
        private SyncTaskStatus completedStatus;
        private Integer completedSuccess;
        private Integer completedFailed;
        private int createdTaskCount;
        private int resetCount;
        private int lastResetModuleCount;

        @Override
        public TaskRegistration createTask(String taskNo, String env, String system, LocalDate date,
                String triggerType, List<ModuleSpec> modules) {
            environment = env;
            systemCode = system;
            logDate = date;
            currentTaskId++;
            createdTaskCount++;
            moduleIds.clear();
            moduleStatus.clear();
            for (ModuleSpec module : modules) {
                moduleIds.put(module.getModuleCode(), Long.valueOf(moduleIds.size() + 1L));
                moduleStatus.put(module.getModuleCode(), SyncTaskStatus.PENDING);
            }
            taskStatus = SyncTaskStatus.PENDING;
            return new TaskRegistration(currentTaskId, new LinkedHashMap<String, Long>(moduleIds));
        }

        @Override
        public RetryTask loadRetryTask(long taskId) {
            if (taskStatus != SyncTaskStatus.FAILED
                    && taskStatus != SyncTaskStatus.PARTIAL_SUCCESS
                    && taskStatus != SyncTaskStatus.CONFLICT) {
                throw new IllegalStateException("Only FAILED, PARTIAL_SUCCESS or CONFLICT task can be retried");
            }
            Map<String, Long> pending = new LinkedHashMap<String, Long>();
            for (Map.Entry<String, SyncTaskStatus> entry : moduleStatus.entrySet()) {
                if (entry.getValue() == SyncTaskStatus.FAILED
                        || entry.getValue() == SyncTaskStatus.CONFLICT) {
                    pending.put(entry.getKey(), moduleIds.get(entry.getKey()));
                }
            }
            return new RetryTask(taskId, environment, systemCode, logDate, taskStatus.name(), pending);
        }

        @Override
        public ModuleOutcomeSummary summarizeModuleOutcomes(long taskId) {
            int success = 0;
            int conflict = 0;
            for (SyncTaskStatus status : moduleStatus.values()) {
                if (status == SyncTaskStatus.SUCCESS) {
                    success++;
                } else if (status == SyncTaskStatus.CONFLICT) {
                    conflict++;
                }
            }
            return new ModuleOutcomeSummary(moduleStatus.size(), success, conflict);
        }

        @Override
        public void resetForRetry(long taskId, int expectedModuleCount) {
            resetCount++;
            lastResetModuleCount = expectedModuleCount;
            taskStatus = SyncTaskStatus.PENDING;
            for (Map.Entry<String, SyncTaskStatus> entry : moduleStatus.entrySet()) {
                if (entry.getValue() == SyncTaskStatus.FAILED
                        || entry.getValue() == SyncTaskStatus.CONFLICT) {
                    entry.setValue(SyncTaskStatus.PENDING);
                }
            }
        }

        @Override
        public void markTaskRunning(long taskId) {
            taskStatus = SyncTaskStatus.RUNNING;
        }

        @Override
        public void markModuleRunning(long moduleTaskId) {
            updateModule(moduleTaskId, SyncTaskStatus.RUNNING);
        }

        @Override
        public void markFileSyncing(long moduleTaskId, LogChannel channel) { }

        @Override
        public void markFileReady(long moduleTaskId, LogChannel channel, String remotePath, String localPath,
                String fileName, long fileSize) { }

        @Override
        public void markFileFailed(long moduleTaskId, LogChannel channel, String errorMessage) { }

        @Override
        public void markModuleSuccess(long moduleTaskId, int totalFileCount) {
            updateModule(moduleTaskId, SyncTaskStatus.SUCCESS);
        }

        @Override
        public void markModuleFailed(long moduleTaskId, int totalFileCount, String errorMessage) {
            updateModule(moduleTaskId, SyncTaskStatus.FAILED);
        }

        @Override
        public void markModuleConflict(long moduleTaskId, String message) {
            updateModule(moduleTaskId, SyncTaskStatus.CONFLICT);
        }

        @Override
        public void completeTask(long taskId, SyncTaskStatus status, int successCount, int failedCount,
                String errorMessage) {
            taskStatus = status;
            completedStatus = status;
            completedSuccess = Integer.valueOf(successCount);
            completedFailed = Integer.valueOf(failedCount);
        }

        private void updateModule(long moduleTaskId, SyncTaskStatus status) {
            for (Map.Entry<String, Long> entry : moduleIds.entrySet()) {
                if (entry.getValue().longValue() == moduleTaskId) {
                    moduleStatus.put(entry.getKey(), status);
                    return;
                }
            }
        }

        private SyncTaskStatus moduleStatus(String moduleCode) {
            return moduleStatus.get(moduleCode);
        }

        private long lastTaskId() {
            return currentTaskId;
        }
    }

    private static final class RecordingTaskRepository implements LogSyncTaskRepository {
        private long sequence;
        @Override
        public TaskRegistration createTask(String taskNo, String environment, String systemCode,
                LocalDate logDate, String triggerType, List<ModuleSpec> modules) {
            Map<String, Long> ids = new LinkedHashMap<String, Long>();
            for (ModuleSpec module : modules) { ids.put(module.getModuleCode(), Long.valueOf(++sequence)); }
            return new TaskRegistration(++sequence, ids);
        }
        @Override public RetryTask loadRetryTask(long taskId) { throw new UnsupportedOperationException(); }
        @Override public ModuleOutcomeSummary summarizeModuleOutcomes(long taskId) {
            throw new UnsupportedOperationException();
        }
        @Override public void resetForRetry(long taskId, int expectedModuleCount) { }
        @Override public void markTaskRunning(long taskId) { }
        @Override public void markModuleRunning(long moduleTaskId) { }
        @Override public void markFileSyncing(long moduleTaskId, LogChannel channel) { }
        @Override public void markFileReady(long moduleTaskId, LogChannel channel, String remotePath, String localPath,
                String fileName, long fileSize) { }
        @Override public void markFileFailed(long moduleTaskId, LogChannel channel, String errorMessage) { }
        @Override public void markModuleSuccess(long moduleTaskId, int totalFileCount) { }
        @Override public void markModuleFailed(long moduleTaskId, int totalFileCount, String errorMessage) { }
        @Override public void markModuleConflict(long moduleTaskId, String message) { }
        @Override public void completeTask(long taskId, SyncTaskStatus status, int successCount,
                int failedCount, String errorMessage) { }
    }
}
