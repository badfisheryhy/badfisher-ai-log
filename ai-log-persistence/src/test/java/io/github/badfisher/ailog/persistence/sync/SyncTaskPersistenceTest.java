package io.github.badfisher.ailog.persistence.sync;

import io.github.badfisher.ailog.persistence.workflow.MybatisPlusLogSyncTaskRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import io.github.badfisher.ailog.domain.sync.LogChannel;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.FileSpec;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.ModuleOutcomeSummary;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.ModuleSpec;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.TaskRegistration;
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

/** Step 1：同步任务、模块任务和文件生命周期持久化测试。 */
class SyncTaskPersistenceTest {
    private AiLogSyncTaskMapper taskMapper;
    private AiLogSyncModuleTaskMapper moduleMapper;
    private AiLogFileRecordMapper fileMapper;
    private AiLogAnalysisTaskMapper analysisTaskMapper;
    private AiLogFileCleanupMapper cleanupMapper;
    private MybatisPlusLogSyncTaskRepository repository;

    @BeforeEach
    void setUp() {
        // Mock Mapper tests must initialize their own lambda metadata, not rely on test order.
        for (Class<?> entity : new Class<?>[] {
                AiLogSyncTaskEntity.class, AiLogSyncModuleTaskEntity.class,
                AiLogFileRecordEntity.class, AiLogAnalysisTaskEntity.class,
                AiLogFileCleanupEntity.class}) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
        taskMapper = mock(AiLogSyncTaskMapper.class);
        moduleMapper = mock(AiLogSyncModuleTaskMapper.class);
        fileMapper = mock(AiLogFileRecordMapper.class);
        analysisTaskMapper = mock(AiLogAnalysisTaskMapper.class);
        cleanupMapper = mock(AiLogFileCleanupMapper.class);
        doAnswer(invocation -> {
            ((AiLogSyncTaskEntity) invocation.getArgument(0)).setId(Long.valueOf(100L));
            return Integer.valueOf(1);
        }).when(taskMapper).insert(any(AiLogSyncTaskEntity.class));
        when(taskMapper.update(any(AiLogSyncTaskEntity.class), any(Wrapper.class))).thenReturn(Integer.valueOf(1));
        when(moduleMapper.update(any(AiLogSyncModuleTaskEntity.class), any(Wrapper.class)))
                .thenReturn(Integer.valueOf(1));
        doAnswer(invocation -> {
            ((AiLogSyncModuleTaskEntity) invocation.getArgument(0)).setId(Long.valueOf(200L));
            return Integer.valueOf(1);
        }).when(moduleMapper).insert(any(AiLogSyncModuleTaskEntity.class));
        doAnswer(invocation -> {
            ((AiLogAnalysisTaskEntity) invocation.getArgument(0)).setId(Long.valueOf(400L));
            return Integer.valueOf(1);
        }).when(analysisTaskMapper).insert(any(AiLogAnalysisTaskEntity.class));
        repository = new MybatisPlusLogSyncTaskRepository(taskMapper, moduleMapper, fileMapper,
                analysisTaskMapper, cleanupMapper, "V1");
    }

    @Test
    void createsTaskModuleAndFileRecordsAsOneAggregate() {
        FileSpec error = new FileSpec(LogChannel.ERROR, "/remote/error.log",
                "/local/ready/error.log", "error.log");
        FileSpec application = new FileSpec(LogChannel.APPLICATION, "/remote/all.log",
                "/local/ready/all.log", "all.log");
        TaskRegistration registration = repository.createTask("SYNC-20260817-ABC", "prod", "demo",
                LocalDate.of(2026, 8, 17), "SCHEDULER",
                Arrays.asList(new ModuleSpec(Long.valueOf(10L), "sample-service",
                        Arrays.asList(error, application))));
        assertThat(registration.getTaskId()).isEqualTo(100L);
        assertThat(registration.requiredModuleTaskId("sample-service")).isEqualTo(200L);
        ArgumentCaptor<AiLogSyncTaskEntity> task = ArgumentCaptor.forClass(AiLogSyncTaskEntity.class);
        verify(taskMapper).insert(task.capture());
        assertThat(task.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(task.getValue().getTotalModuleCount()).isEqualTo(1);
        ArgumentCaptor<AiLogFileRecordEntity> files = ArgumentCaptor.forClass(AiLogFileRecordEntity.class);
        verify(fileMapper, org.mockito.Mockito.times(2)).insert(files.capture());
        assertThat(files.getAllValues()).extracting(AiLogFileRecordEntity::getSyncStatus)
                .containsOnly("PENDING");
        assertThat(files.getAllValues()).extracting(AiLogFileRecordEntity::getParseStatus)
                .containsOnly("WAITING");
    }

    @Test
    void rejectsExistingTaskForTheSameBusinessDateBeforeInsert() {
        AiLogSyncTaskEntity existing = new AiLogSyncTaskEntity();
        existing.setId(Long.valueOf(99L));
        when(taskMapper.selectByBusinessDate("prod", "demo", LocalDate.of(2026, 8, 17)))
                .thenReturn(existing);

        assertThatThrownBy(() -> repository.createTask("SYNC-20260817-NEW", "prod", "demo",
                LocalDate.of(2026, 8, 17), "MANUAL", Arrays.asList(moduleSpec())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同一环境、系统和日志日期只能创建一个同步父任务")
                .hasMessageContaining("使用原任务重试");

        verify(taskMapper, never()).insert(any(AiLogSyncTaskEntity.class));
        verify(moduleMapper, never()).insert(any(AiLogSyncModuleTaskEntity.class));
    }

    @Test
    void translatesConcurrentBusinessDateConflictToReadableError() {
        doThrow(new DuplicateKeyException("uk_sync_scope"))
                .when(taskMapper).insert(any(AiLogSyncTaskEntity.class));

        assertThatThrownBy(() -> repository.createTask("SYNC-20260817-RACE", "prod", "demo",
                LocalDate.of(2026, 8, 17), "SCHEDULER", Arrays.asList(moduleSpec())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同一环境、系统和日志日期只能创建一个同步父任务")
                .hasCauseInstanceOf(DuplicateKeyException.class);

        verify(moduleMapper, never()).insert(any(AiLogSyncModuleTaskEntity.class));
    }

    @Test
    void preservesUnrelatedUniqueKeyFailure() {
        DuplicateKeyException taskNumberConflict =
                new DuplicateKeyException("Duplicate entry for uk_task_no");
        doThrow(taskNumberConflict).when(taskMapper).insert(any(AiLogSyncTaskEntity.class));

        assertThatThrownBy(() -> repository.createTask("SYNC-DUPLICATE", "prod", "demo",
                LocalDate.of(2026, 8, 17), "SCHEDULER", Arrays.asList(moduleSpec())))
                .isSameAs(taskNumberConflict);

        verify(moduleMapper, never()).insert(any(AiLogSyncModuleTaskEntity.class));
    }

    @Test
    void recordsRunningReadyAndSuccessfulCompletion() {
        AiLogFileRecordEntity readyFile = new AiLogFileRecordEntity();
        readyFile.setId(Long.valueOf(300L));
        readyFile.setSyncTaskId(Long.valueOf(100L));
        readyFile.setSyncModuleTaskId(Long.valueOf(200L));
        readyFile.setEnvironment("prod");
        readyFile.setSystemCode("demo");
        readyFile.setModuleCode("sample-service");
        readyFile.setLogDate(LocalDate.of(2026, 8, 17));
        readyFile.setLocalPath("/local/ready/error.log");
        when(fileMapper.selectOne(any(Wrapper.class))).thenReturn(readyFile);
        when(analysisTaskMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(cleanupMapper.selectCount(any(Wrapper.class))).thenReturn(Long.valueOf(0L));
        repository.markTaskRunning(100L);
        repository.markModuleRunning(200L);
        repository.markFileSyncing(200L, LogChannel.ERROR);
        repository.markFileReady(200L, LogChannel.ERROR, "/remote/error.log",
                "/local/ready/error.log", "error.log", 128L);
        repository.markModuleSuccess(200L, 1);
        repository.completeTask(100L, SyncTaskStatus.SUCCESS, 1, 0, null);
        verify(taskMapper, org.mockito.Mockito.times(2))
                .update(any(AiLogSyncTaskEntity.class), any(Wrapper.class));
        verify(moduleMapper).update(any(AiLogSyncModuleTaskEntity.class), any(Wrapper.class));
        verify(moduleMapper).updateById(any(AiLogSyncModuleTaskEntity.class));
        verify(fileMapper, org.mockito.Mockito.times(2)).update(any(AiLogFileRecordEntity.class), any(Wrapper.class));
        ArgumentCaptor<AiLogAnalysisTaskEntity> analysisTask = ArgumentCaptor.forClass(
                AiLogAnalysisTaskEntity.class);
        verify(analysisTaskMapper).insert(analysisTask.capture());
        assertThat(analysisTask.getValue().getStatus()).isEqualTo("WAITING");
        assertThat(analysisTask.getValue().getFileRecordId()).isEqualTo(300L);
        ArgumentCaptor<AiLogFileCleanupEntity> cleanup = ArgumentCaptor.forClass(
                AiLogFileCleanupEntity.class);
        verify(cleanupMapper).insert(cleanup.capture());
        assertThat(cleanup.getValue().getStatus()).isEqualTo("WAITING_ANALYSIS");
        assertThat(cleanup.getValue().getAnalysisTaskId()).isEqualTo(400L);
    }

    @Test
    void preservesReadyCountWhenLaterFileFails() {
        when(fileMapper.selectCount(any(Wrapper.class))).thenReturn(Long.valueOf(1L));
        repository.markFileFailed(200L, LogChannel.APPLICATION, "rsync failed");
        repository.markModuleFailed(200L, 2, "rsync failed");
        repository.completeTask(100L, SyncTaskStatus.FAILED, 0, 1, "rsync failed");
        ArgumentCaptor<AiLogSyncModuleTaskEntity> module = ArgumentCaptor.forClass(AiLogSyncModuleTaskEntity.class);
        verify(moduleMapper).updateById(module.capture());
        assertThat(module.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(module.getValue().getSuccessFileCount()).isEqualTo(1);
        assertThat(module.getValue().getFailedFileCount()).isEqualTo(1);
        assertThat(module.getValue().getErrorMessage()).isEqualTo("rsync failed");
    }

    @Test
    void summarizeModuleOutcomesCountsSuccessModulesOnly() {
        when(moduleMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(
                moduleTask("sample-service", "SUCCESS"), moduleTask("asinking", "FAILED"),
                moduleTask("auth", "SUCCESS"), moduleTask("order", "CONFLICT")));

        ModuleOutcomeSummary summary = repository.summarizeModuleOutcomes(100L);

        assertThat(summary.getTotalModules()).isEqualTo(4);
        assertThat(summary.getSuccessModules()).isEqualTo(2);
        assertThat(summary.getFailedModules()).isEqualTo(1);
        assertThat(summary.getConflictModules()).isEqualTo(1);
    }

    @Test
    void recordsLockConflictWithoutFailedFileCount() {
        repository.markModuleConflict(200L, "Synchronization is already running");

        ArgumentCaptor<AiLogSyncModuleTaskEntity> module =
                ArgumentCaptor.forClass(AiLogSyncModuleTaskEntity.class);
        verify(moduleMapper).updateById(module.capture());
        assertThat(module.getValue().getStatus()).isEqualTo("CONFLICT");
        assertThat(module.getValue().getFailedFileCount()).isZero();
        assertThat(module.getValue().getSuccessFileCount()).isZero();
    }

    @Test
    void readyCallbackPreservesAnExistingParsingResult() {
        AiLogFileRecordEntity file = new AiLogFileRecordEntity();
        file.setId(Long.valueOf(300L));
        file.setParseStatus("PARSED");
        AiLogAnalysisTaskEntity task = new AiLogAnalysisTaskEntity();
        task.setId(Long.valueOf(400L));
        task.setStatus("SUCCESS");
        when(fileMapper.selectOne(any(Wrapper.class))).thenReturn(file);
        when(analysisTaskMapper.selectOne(any(Wrapper.class))).thenReturn(task);
        when(cleanupMapper.selectCount(any(Wrapper.class))).thenReturn(Long.valueOf(1L));

        repository.markFileReady(200L, LogChannel.ERROR, "/remote/error.log",
                "/local/ready/error.log", "error.log", 128L);

        ArgumentCaptor<AiLogFileRecordEntity> update =
                ArgumentCaptor.forClass(AiLogFileRecordEntity.class);
        verify(fileMapper).update(update.capture(), any(Wrapper.class));
        assertThat(update.getValue().getSyncStatus()).isEqualTo("READY");
        assertThat(update.getValue().getParseStatus()).isNull();
        verify(analysisTaskMapper, never()).insert(any(AiLogAnalysisTaskEntity.class));
        verify(cleanupMapper, never()).insert(any(AiLogFileCleanupEntity.class));
    }

    @Test
    void concurrentRetryCannotResetModuleOrFileStates() {
        when(taskMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> repository.resetForRetry(100L, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("状态不再可重试");

        verify(moduleMapper, never()).update(any(), any(Wrapper.class));
        verify(fileMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void concurrentModuleChangeRejectsPartialRetryReset() {
        when(taskMapper.update(isNull(), any(Wrapper.class))).thenReturn(Integer.valueOf(1));
        when(moduleMapper.update(any(AiLogSyncModuleTaskEntity.class), any(Wrapper.class)))
                .thenReturn(Integer.valueOf(1));

        assertThatThrownBy(() -> repository.resetForRetry(100L, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝部分重置")
                .hasMessageContaining("expected=2")
                .hasMessageContaining("actual=1");

        verify(fileMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void runningTransitionsRejectStaleParentAndModuleStates() {
        when(taskMapper.update(any(AiLogSyncTaskEntity.class), any(Wrapper.class)))
                .thenReturn(Integer.valueOf(0));

        assertThatThrownBy(() -> repository.markTaskRunning(100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("父任务进入RUNNING失败")
                .hasMessageContaining("不是PENDING");

        when(moduleMapper.update(any(AiLogSyncModuleTaskEntity.class), any(Wrapper.class)))
                .thenReturn(Integer.valueOf(0));

        assertThatThrownBy(() -> repository.markModuleRunning(200L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模块进入RUNNING失败")
                .hasMessageContaining("不是PENDING");
    }

    @Test
    void loadsOnlyRetryableModulesAndPreservesSuccessfulModule() {
        AiLogSyncTaskEntity task = syncTask("PARTIAL_SUCCESS");
        when(taskMapper.selectById(Long.valueOf(100L))).thenReturn(task);
        when(moduleMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(
                moduleTask("sample-service", "SUCCESS"), moduleTask("asinking", "FAILED")));

        io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository.RetryTask retry =
                repository.loadRetryTask(100L);

        assertThat(retry.getModuleTaskIds()).containsOnlyKeys("asinking");
        assertThat(retry.getModuleTaskIds()).doesNotContainKey("sample-service");
    }

    @Test
    void rejectsRunningParentOrModuleBeforeRetryReset() {
        when(taskMapper.selectById(Long.valueOf(100L))).thenReturn(syncTask("RUNNING"));

        assertThatThrownBy(() -> repository.loadRetryTask(100L))
                .hasMessageContaining("状态不可重试")
                .hasMessageContaining("status=RUNNING");
        verify(moduleMapper, never()).selectList(any(Wrapper.class));

        org.mockito.Mockito.reset(moduleMapper);
        when(taskMapper.selectById(Long.valueOf(100L))).thenReturn(syncTask("PARTIAL_SUCCESS"));
        when(moduleMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(
                moduleTask("sample-service", "SUCCESS"), moduleTask("asinking", "RUNNING")));

        assertThatThrownBy(() -> repository.loadRetryTask(100L))
                .hasMessageContaining("拒绝重置RUNNING")
                .hasMessageContaining("moduleCode=asinking");
    }

    @Test
    void retryResetPreservesParentCountsFilesAndDownstreamHistory() {
        when(taskMapper.update(isNull(), any(Wrapper.class))).thenReturn(Integer.valueOf(1));
        ArgumentCaptor<AiLogSyncTaskEntity> parent =
                ArgumentCaptor.forClass(AiLogSyncTaskEntity.class);
        ArgumentCaptor<AiLogSyncModuleTaskEntity> module =
                ArgumentCaptor.forClass(AiLogSyncModuleTaskEntity.class);

        repository.resetForRetry(100L, 1);

        verify(taskMapper).update(isNull(), any(Wrapper.class));
        verify(moduleMapper).update(module.capture(), any(Wrapper.class));
        assertThat(module.getValue().getStatus()).isEqualTo("PENDING");
        verify(fileMapper, never()).update(any(AiLogFileRecordEntity.class), any(Wrapper.class));
        verifyNoInteractions(analysisTaskMapper, cleanupMapper);

        repository.markTaskRunning(100L);
        verify(taskMapper, org.mockito.Mockito.times(2))
                .update(parent.capture(), any(Wrapper.class));
        AiLogSyncTaskEntity running = parent.getAllValues().get(1);
        assertThat(running.getStatus()).isEqualTo("RUNNING");
        assertThat(running.getSuccessModuleCount()).isNull();
        assertThat(running.getFailedModuleCount()).isNull();
    }

    private static AiLogSyncModuleTaskEntity moduleTask(String moduleCode, String status) {
        AiLogSyncModuleTaskEntity module = new AiLogSyncModuleTaskEntity();
        module.setId(Long.valueOf(200L));
        module.setSyncTaskId(Long.valueOf(100L));
        module.setModuleCode(moduleCode);
        module.setStatus(status);
        return module;
    }

    private static AiLogSyncTaskEntity syncTask(String status) {
        AiLogSyncTaskEntity task = new AiLogSyncTaskEntity();
        task.setId(Long.valueOf(100L));
        task.setEnvironment("prod");
        task.setSystemCode("demo");
        task.setLogDate(LocalDate.of(2026, 8, 17));
        task.setStatus(status);
        return task;
    }

    private static ModuleSpec moduleSpec() {
        FileSpec file = new FileSpec(LogChannel.ERROR, "/remote/error.log",
                "/local/ready/error.log", "error.log");
        return new ModuleSpec(Long.valueOf(10L), "sample-service", Arrays.asList(file));
    }
}
