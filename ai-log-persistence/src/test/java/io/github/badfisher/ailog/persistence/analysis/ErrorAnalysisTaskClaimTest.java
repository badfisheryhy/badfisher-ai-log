package io.github.badfisher.ailog.persistence.analysis;

import io.github.badfisher.ailog.persistence.workflow.MybatisPlusErrorAnalysisRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Collections;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository.ClaimedFile;
import io.github.badfisher.ailog.domain.text.Sha256;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogAnalysisTaskEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogAnalysisTaskMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;
import io.github.badfisher.ailog.persistence.cleanup.mapper.AiLogFileCleanupMapper;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogFileRecordEntity;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogFileRecordMapper;

/** ERROR 分析任务认领生命周期测试。 */
class ErrorAnalysisTaskClaimTest {

    private AiLogFileRecordMapper fileMapper;
    private AiLogAnalysisTaskMapper taskMapper;
    private AiLogIssueGroupGovernanceMapper governanceMapper;
    private MybatisPlusErrorAnalysisRepository repository;

    @BeforeEach
    void setUp() {
        initializeTableMetadata(AiLogFileRecordEntity.class);
        initializeTableMetadata(AiLogAnalysisTaskEntity.class);
        fileMapper = mock(AiLogFileRecordMapper.class);
        taskMapper = mock(AiLogAnalysisTaskMapper.class);
        governanceMapper = mock(AiLogIssueGroupGovernanceMapper.class);
        repository = new MybatisPlusErrorAnalysisRepository(fileMapper, taskMapper,
                mock(AiLogIssueGroupMapper.class), mock(AiLogErrorEventMapper.class),
                mock(AiLogFileCleanupMapper.class), governanceMapper);
    }

    /** 为纯 Mock 测试初始化 MyBatis-Plus Lambda 字段缓存。 */
    private static void initializeTableMetadata(Class<?> entityType) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, entityType);
    }

    @Test
    void claimsExistingWaitingTaskWithoutCreatingAnotherTask() {
        AiLogFileRecordEntity file = readyFile();
        AiLogAnalysisTaskEntity task = waitingTask();
        when(fileMapper.selectClaimCandidates(anyString(), anyString(),
                any(LocalDate.class), eq("ERROR"), eq("READY"),
                eq("WAITING"), anyInt()))
                        .thenReturn(Collections.singletonList(file));
        when(fileMapper.update(any(), any(Wrapper.class))).thenReturn(Integer.valueOf(1));
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(task);
        when(taskMapper.update(any(), any(Wrapper.class))).thenReturn(Integer.valueOf(1));

        ClaimedFile claimed = repository.claimNext("prod", "demo",
                LocalDate.of(2026, 8, 1), "V1");

        assertThat(claimed).isNotNull();
        assertThat(claimed.getAnalysisTaskId()).isEqualTo(400L);
        assertThat(claimed.getFileRecordId()).isEqualTo(300L);
        verify(taskMapper, never()).insert(any(AiLogAnalysisTaskEntity.class));
    }

    @Test
    void rejectsReadyFileWithoutAnalysisTask() {
        AiLogFileRecordEntity file = readyFile();
        when(fileMapper.selectClaimCandidates(anyString(), anyString(),
                any(LocalDate.class), eq("ERROR"), eq("READY"),
                eq("WAITING"), anyInt()))
                        .thenReturn(Collections.singletonList(file));
        when(fileMapper.update(any(), any(Wrapper.class))).thenReturn(Integer.valueOf(1));
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> repository.claimNext("prod", "demo",
                LocalDate.of(2026, 8, 1), "V1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Analysis task not found");
        verify(taskMapper, never()).insert(any(AiLogAnalysisTaskEntity.class));
    }

    @Test
    void createsDirectTaskWithDeterministicNumberAndNullableLifecycleIds() {
        LocalDate logDate = LocalDate.of(2026, 9, 13);
        String localPath = "E:/logs/20260913_sample-service-err.log";
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            AiLogAnalysisTaskEntity task = invocation.getArgument(0);
            task.setId(Long.valueOf(400L));
            return Integer.valueOf(1);
        }).when(taskMapper).insert(any(AiLogAnalysisTaskEntity.class));
        when(taskMapper.update(any(), any(Wrapper.class))).thenReturn(Integer.valueOf(1));

        ClaimedFile claimed = repository.claimDirect("prod", "demo", "sample-service", logDate,
                localPath, "V1");

        String normalizedPath = Paths.get(localPath).toAbsolutePath().normalize().toString();
        String identity = "prod\ndemo\nsample-service\n" + logDate + "\n" + normalizedPath + "\nV1";
        String expectedTaskNo = "DIRECT-" + Sha256.sha256(identity).substring(0, 56);
        org.mockito.ArgumentCaptor<AiLogAnalysisTaskEntity> inserted =
                org.mockito.ArgumentCaptor.forClass(AiLogAnalysisTaskEntity.class);
        verify(taskMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTaskNo()).isEqualTo(expectedTaskNo).hasSize(63);
        assertThat(inserted.getValue().getFileRecordId()).isNull();
        assertThat(inserted.getValue().getSyncTaskId()).isNull();
        assertThat(inserted.getValue().getSyncModuleTaskId()).isNull();
        assertThat(claimed).isNotNull();
        assertThat(claimed.isManagedFile()).isFalse();
        assertThat(claimed.getFileRecordId()).isNull();
        assertThat(claimed.getLocalPath()).isEqualTo(normalizedPath);
        verifyNoInteractions(fileMapper);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {".log.gz", ".log.1.gz", ".log.2.GZ"})
    void gzipDirectPathUsesSameTaskIdentityAsPlainLog(String suffix) {
        LocalDate logDate = LocalDate.of(2026, 9, 13);
        String gzipPath = "E:/logs/20260913_sample-service-err" + suffix;
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            AiLogAnalysisTaskEntity task = invocation.getArgument(0);
            task.setId(Long.valueOf(400L));
            return Integer.valueOf(1);
        }).when(taskMapper).insert(any(AiLogAnalysisTaskEntity.class));
        when(taskMapper.update(any(), any(Wrapper.class))).thenReturn(Integer.valueOf(1));

        ClaimedFile claimed = repository.claimDirect("prod", "demo", "sample-service", logDate,
                gzipPath, "V1");

        String normalizedPath = Paths.get(gzipPath).toAbsolutePath().normalize().toString();
        String identityPath = normalizedPath.substring(0, normalizedPath.length() - 3);
        String identity = "prod\ndemo\nsample-service\n" + logDate + "\n" + identityPath + "\nV1";
        String expectedTaskNo = "DIRECT-" + Sha256.sha256(identity).substring(0, 56);
        org.mockito.ArgumentCaptor<AiLogAnalysisTaskEntity> inserted =
                org.mockito.ArgumentCaptor.forClass(AiLogAnalysisTaskEntity.class);
        verify(taskMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTaskNo()).isEqualTo(expectedTaskNo);
        assertThat(claimed.getLocalPath()).isEqualTo(normalizedPath);
    }

    @Test
    void skipsDirectClaimWhenTheSameFileAlreadySucceeded() {
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(directTask("SUCCESS"));

        ClaimedFile claimed = repository.claimDirect("prod", "demo", "sample-service",
                LocalDate.of(2026, 9, 13), "E:/logs/error.log", "V1");

        assertThat(claimed).isNull();
        verify(taskMapper).selectOne(any(Wrapper.class));
        verify(taskMapper, never()).insert(any(AiLogAnalysisTaskEntity.class));
        verify(taskMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void failedBusinessResultDoesNotPreventDifferentFingerprintDirectClaim() {
        // SUCCESS 业务键查询为 0：历史 FAILED 不参与成功去重，V2 可以创建新任务。

        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            AiLogAnalysisTaskEntity task = invocation.getArgument(0);
            task.setId(Long.valueOf(400L));
            return Integer.valueOf(1);
        }).when(taskMapper).insert(any(AiLogAnalysisTaskEntity.class));
        when(taskMapper.update(any(), any(Wrapper.class))).thenReturn(Integer.valueOf(1));

        ClaimedFile claimed = repository.claimDirect("prod", "demo", "sample-service",
                LocalDate.of(2026, 9, 13), "E:/logs/error.log", "V2");

        assertThat(claimed).isNotNull();
        verify(taskMapper, never()).selectCount(any(Wrapper.class));
    }

    @Test
    void skipsDirectTaskThatIsAlreadyTerminalOrParsing() {
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(
                directTask("SUCCESS"), directTask("FAILED"), directTask("PARSING"));

        assertThat(repository.claimDirect("prod", "demo", "sample-service",
                LocalDate.of(2026, 9, 13), "E:/logs/error.log", "V1")).isNull();
        assertThat(repository.claimDirect("prod", "demo", "sample-service",
                LocalDate.of(2026, 9, 13), "E:/logs/error.log", "V1")).isNull();
        assertThat(repository.claimDirect("prod", "demo", "sample-service",
                LocalDate.of(2026, 9, 13), "E:/logs/error.log", "V1")).isNull();

        verify(taskMapper, never()).insert(any(AiLogAnalysisTaskEntity.class));
        verify(taskMapper, never()).update(any(), any(Wrapper.class));
    }

    private static AiLogFileRecordEntity readyFile() {
        AiLogFileRecordEntity file = new AiLogFileRecordEntity();
        file.setId(Long.valueOf(300L));
        file.setSyncTaskId(Long.valueOf(100L));
        file.setSyncModuleTaskId(Long.valueOf(200L));
        file.setEnvironment("prod");
        file.setSystemCode("demo");
        file.setModuleCode("sample-service");
        file.setLogDate(LocalDate.of(2026, 8, 1));
        file.setLocalPath("E:/runtime-data/prod/demo/sample-service/20260801/ready/error/error.log");
        return file;
    }

    private static AiLogAnalysisTaskEntity waitingTask() {
        AiLogAnalysisTaskEntity task = new AiLogAnalysisTaskEntity();
        task.setId(Long.valueOf(400L));
        task.setFileRecordId(Long.valueOf(300L));
        task.setStatus("WAITING");
        task.setFingerprintVersion("V1");
        task.setRetryCount(Integer.valueOf(0));
        return task;
    }

    private static AiLogAnalysisTaskEntity directTask(String status) {
        AiLogAnalysisTaskEntity task = new AiLogAnalysisTaskEntity();
        task.setId(Long.valueOf(400L));
        task.setTaskNo("DIRECT-existing");
        task.setStatus(status);
        task.setFingerprintVersion("V1");
        return task;
    }
}
