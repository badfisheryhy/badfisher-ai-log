package io.github.badfisher.ailog.persistence.workflow;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.badfisher.ailog.domain.analysis.AnalysisTaskStatus;
import io.github.badfisher.ailog.domain.cleanup.FileCleanupRepository;
import io.github.badfisher.ailog.domain.cleanup.FileCleanupStatus;
import io.github.badfisher.ailog.domain.sync.LogFileParseStatus;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogAnalysisTaskEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogAnalysisTaskMapper;
import io.github.badfisher.ailog.persistence.cleanup.entity.AiLogFileCleanupEntity;
import io.github.badfisher.ailog.persistence.cleanup.mapper.AiLogFileCleanupMapper;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogFileRecordEntity;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogFileRecordMapper;

/** 基于 MyBatis-Plus 的本地日志文件清理任务仓储。 */
public class MybatisPlusFileCleanupRepository implements FileCleanupRepository {

    private static final int CLAIM_SCAN_LIMIT = 20;

    private final AiLogFileCleanupMapper cleanupMapper;
    private final AiLogFileRecordMapper fileMapper;
    private final AiLogAnalysisTaskMapper analysisTaskMapper;

    public MybatisPlusFileCleanupRepository(AiLogFileCleanupMapper cleanups,
            AiLogFileRecordMapper files, AiLogAnalysisTaskMapper analysisTasks) {
        cleanupMapper = cleanups;
        fileMapper = files;
        analysisTaskMapper = analysisTasks;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClaimedCleanup claimByFileRecordId(long fileRecordId) {
        AiLogFileCleanupEntity cleanup = cleanupMapper.selectOne(
                Wrappers.<AiLogFileCleanupEntity>lambdaQuery()
                        .eq(AiLogFileCleanupEntity::getFileRecordId, Long.valueOf(fileRecordId)));
        if (cleanup == null
                || !FileCleanupStatus.WAITING.name().equals(cleanup.getStatus())
                || !isAnalysisSuccessful(cleanup)) {
            return null;
        }
        return claim(cleanup, LocalDateTime.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClaimedCleanup claimNext() {
        LocalDateTime now = LocalDateTime.now();
        List<AiLogFileCleanupEntity> candidates = cleanupMapper.selectClaimCandidates(
                now, CLAIM_SCAN_LIMIT);
        for (AiLogFileCleanupEntity candidate : candidates) {
            ClaimedCleanup claimed = claimEligible(candidate, now);
            if (claimed != null) {
                return claimed;
            }
        }
        return null;
    }

    private ClaimedCleanup claimEligible(AiLogFileCleanupEntity cleanup, LocalDateTime now) {
        if (cleanup == null || !isDue(cleanup, now) || !isAnalysisSuccessful(cleanup)) {
            return null;
        }
        return claim(cleanup, now);
    }

    /** 将可执行的清理任务原子更新为删除中。 */
    private ClaimedCleanup claim(AiLogFileCleanupEntity cleanup, LocalDateTime now) {
        int updated = cleanupMapper.update(null,
                Wrappers.<AiLogFileCleanupEntity>lambdaUpdate()
                        .eq(AiLogFileCleanupEntity::getId, cleanup.getId())
                        .eq(AiLogFileCleanupEntity::getStatus, cleanup.getStatus())
                        .set(AiLogFileCleanupEntity::getStatus, FileCleanupStatus.DELETING.name())
                        .set(AiLogFileCleanupEntity::getDeletingTime, now)
                        .set(AiLogFileCleanupEntity::getLastErrorMessage, null));
        if (updated != 1) {
            return null;
        }
        int retries = cleanup.getRetryCount() == null ? 0 : cleanup.getRetryCount().intValue();
        return new ClaimedCleanup(cleanup.getId().longValue(), cleanup.getFileRecordId().longValue(),
                cleanup.getLocalPath(), retries);
    }

    private boolean isDue(AiLogFileCleanupEntity cleanup, LocalDateTime now) {
        if (FileCleanupStatus.WAITING.name().equals(cleanup.getStatus())) {
            return cleanup.getScheduledTime() != null && !cleanup.getScheduledTime().isAfter(now);
        }
        return FileCleanupStatus.RETRY_WAITING.name().equals(cleanup.getStatus())
                && cleanup.getNextRetryTime() != null
                && !cleanup.getNextRetryTime().isAfter(now);
    }

    private boolean isAnalysisSuccessful(AiLogFileCleanupEntity cleanup) {
        if (cleanup.getAnalysisTaskId() == null) {
            return false;
        }
        AiLogFileRecordEntity file = fileMapper.selectById(cleanup.getFileRecordId());
        AiLogAnalysisTaskEntity task = analysisTaskMapper.selectById(cleanup.getAnalysisTaskId());
        return file != null && LogFileParseStatus.PARSED.name().equals(file.getParseStatus())
                && task != null && AnalysisTaskStatus.SUCCESS.name().equals(task.getStatus())
                && cleanup.getFileRecordId().equals(task.getFileRecordId());
    }

    @Override
    public void markDeleted(long cleanupId, String remark) {
        cleanupMapper.update(null, Wrappers.<AiLogFileCleanupEntity>lambdaUpdate()
                .eq(AiLogFileCleanupEntity::getId, Long.valueOf(cleanupId))
                .eq(AiLogFileCleanupEntity::getStatus, FileCleanupStatus.DELETING.name())
                .set(AiLogFileCleanupEntity::getStatus, FileCleanupStatus.DELETED.name())
                .set(AiLogFileCleanupEntity::getDeletedTime, LocalDateTime.now())
                .set(AiLogFileCleanupEntity::getManualRequired, Boolean.FALSE)
                .set(AiLogFileCleanupEntity::getRemark, remark));
    }

    @Override
    public void markRetry(long cleanupId, int retryCount, LocalDateTime nextRetryTime,
            String errorMessage) {
        cleanupMapper.update(null, Wrappers.<AiLogFileCleanupEntity>lambdaUpdate()
                .eq(AiLogFileCleanupEntity::getId, Long.valueOf(cleanupId))
                .eq(AiLogFileCleanupEntity::getStatus, FileCleanupStatus.DELETING.name())
                .set(AiLogFileCleanupEntity::getStatus, FileCleanupStatus.RETRY_WAITING.name())
                .set(AiLogFileCleanupEntity::getRetryCount, Integer.valueOf(retryCount))
                .set(AiLogFileCleanupEntity::getNextRetryTime, nextRetryTime)
                .set(AiLogFileCleanupEntity::getLastErrorMessage, errorMessage));
    }

    @Override
    public void markManualRequired(long cleanupId, int retryCount, String errorMessage) {
        cleanupMapper.update(null, Wrappers.<AiLogFileCleanupEntity>lambdaUpdate()
                .eq(AiLogFileCleanupEntity::getId, Long.valueOf(cleanupId))
                .eq(AiLogFileCleanupEntity::getStatus, FileCleanupStatus.DELETING.name())
                .set(AiLogFileCleanupEntity::getStatus, FileCleanupStatus.MANUAL_REQUIRED.name())
                .set(AiLogFileCleanupEntity::getRetryCount, Integer.valueOf(retryCount))
                .set(AiLogFileCleanupEntity::getManualRequired, Boolean.TRUE)
                .set(AiLogFileCleanupEntity::getLastErrorMessage, errorMessage));
    }
}
