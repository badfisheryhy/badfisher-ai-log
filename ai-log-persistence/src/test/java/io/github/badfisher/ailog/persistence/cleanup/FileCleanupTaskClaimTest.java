package io.github.badfisher.ailog.persistence.cleanup;

import io.github.badfisher.ailog.persistence.workflow.MybatisPlusFileCleanupRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.cleanup.FileCleanupRepository.ClaimedCleanup;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogAnalysisTaskEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogAnalysisTaskMapper;
import io.github.badfisher.ailog.persistence.cleanup.entity.AiLogFileCleanupEntity;
import io.github.badfisher.ailog.persistence.cleanup.mapper.AiLogFileCleanupMapper;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogFileRecordEntity;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogFileRecordMapper;

/** 清理任务通过XML Mapper查询认领候选的回归测试。 */
class FileCleanupTaskClaimTest {

    private AiLogFileCleanupMapper cleanupMapper;
    private AiLogFileRecordMapper fileMapper;
    private AiLogAnalysisTaskMapper taskMapper;
    private MybatisPlusFileCleanupRepository repository;

    @BeforeEach
    void setUp() {
        initializeTableMetadata(AiLogFileCleanupEntity.class);
        cleanupMapper = mock(AiLogFileCleanupMapper.class);
        fileMapper = mock(AiLogFileRecordMapper.class);
        taskMapper = mock(AiLogAnalysisTaskMapper.class);
        repository = new MybatisPlusFileCleanupRepository(cleanupMapper, fileMapper, taskMapper);
    }

    @Test
    void claimNextUsesXmlCandidateQueryAndClaimsEligibleRecord() {
        AiLogFileCleanupEntity cleanup = cleanup();
        when(cleanupMapper.selectClaimCandidates(any(LocalDateTime.class), eq(20)))
                .thenReturn(Collections.singletonList(cleanup));
        when(fileMapper.selectById(Long.valueOf(2L))).thenReturn(parsedFile());
        when(taskMapper.selectById(Long.valueOf(3L))).thenReturn(successTask());
        when(cleanupMapper.update(any(), any(Wrapper.class))).thenReturn(Integer.valueOf(1));

        ClaimedCleanup claimed = repository.claimNext();

        assertThat(claimed).isNotNull();
        assertThat(claimed.getCleanupId()).isEqualTo(1L);
        verify(cleanupMapper).selectClaimCandidates(any(LocalDateTime.class), eq(20));
    }

    @Test
    void claimByFileRecordIdDoesNotWaitForRoundedScheduleTime() {
        AiLogFileCleanupEntity cleanup = cleanup();
        cleanup.setScheduledTime(LocalDateTime.now().plusSeconds(1L));
        when(cleanupMapper.selectOne(any(Wrapper.class))).thenReturn(cleanup);
        when(fileMapper.selectById(Long.valueOf(2L))).thenReturn(parsedFile());
        when(taskMapper.selectById(Long.valueOf(3L))).thenReturn(successTask());
        when(cleanupMapper.update(any(), any(Wrapper.class))).thenReturn(Integer.valueOf(1));

        ClaimedCleanup claimed = repository.claimByFileRecordId(2L);

        assertThat(claimed).isNotNull();
        assertThat(claimed.getCleanupId()).isEqualTo(1L);
    }

    private static AiLogFileCleanupEntity cleanup() {
        AiLogFileCleanupEntity cleanup = new AiLogFileCleanupEntity();
        cleanup.setId(Long.valueOf(1L));
        cleanup.setFileRecordId(Long.valueOf(2L));
        cleanup.setAnalysisTaskId(Long.valueOf(3L));
        cleanup.setLocalPath("E:/logs/error.log");
        cleanup.setStatus("WAITING");
        cleanup.setScheduledTime(LocalDateTime.now().minusMinutes(1L));
        cleanup.setRetryCount(Integer.valueOf(0));
        return cleanup;
    }

    private static AiLogFileRecordEntity parsedFile() {
        AiLogFileRecordEntity file = new AiLogFileRecordEntity();
        file.setId(Long.valueOf(2L));
        file.setParseStatus("PARSED");
        return file;
    }

    private static AiLogAnalysisTaskEntity successTask() {
        AiLogAnalysisTaskEntity task = new AiLogAnalysisTaskEntity();
        task.setId(Long.valueOf(3L));
        task.setFileRecordId(Long.valueOf(2L));
        task.setStatus("SUCCESS");
        return task;
    }

    private static void initializeTableMetadata(Class<?> entityType) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, entityType);
    }
}
