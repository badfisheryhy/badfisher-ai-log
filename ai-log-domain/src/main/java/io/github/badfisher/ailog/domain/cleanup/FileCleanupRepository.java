package io.github.badfisher.ailog.domain.cleanup;

import lombok.Getter;

import java.time.LocalDateTime;

/** 本地日志文件清理任务持久化端口。 */
public interface FileCleanupRepository {

    /**
     * 解析成功后立即领取指定文件的待清理任务。
     *
     * <p>即时清理不等待补偿计划时间；任务不可执行时返回 {@code null}。</p>
     */
    ClaimedCleanup claimByFileRecordId(long fileRecordId);

    /** 领取一条已解析成功且到达执行时间的补偿清理任务。 */
    ClaimedCleanup claimNext();

    /** 将文件不存在或删除成功的任务标记为已清理。 */
    void markDeleted(long cleanupId, String remark);

    /** 记录可重试的删除失败并安排下一次执行时间。 */
    void markRetry(long cleanupId, int retryCount, LocalDateTime nextRetryTime,
            String errorMessage);

    /** 记录不可自动恢复的删除失败。 */
    void markManualRequired(long cleanupId, int retryCount, String errorMessage);

    /** 已被当前执行器原子领取的清理任务。 */
    @Getter
    final class ClaimedCleanup {
        private final long cleanupId;
        private final long fileRecordId;
        private final String localPath;
        private final int retryCount;

        public ClaimedCleanup(long id, long fileId, String path, int retries) {
            cleanupId = id;
            fileRecordId = fileId;
            localPath = path;
            retryCount = retries;
        }

    }
}
