package io.github.badfisher.ailog.application.cleanup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

import lombok.extern.slf4j.Slf4j;

import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.domain.cleanup.FileCleanupRepository;
import io.github.badfisher.ailog.domain.cleanup.FileCleanupRepository.ClaimedCleanup;

/**
 * 安全删除已解析完成的本地日志文件。
 * <p>
 * 数据库仓储负责领取与状态流转，本服务只在配置根目录内删除普通文件。
 */
@Slf4j
public final class FileCleanupService {

    private static final int MAXIMUM_BATCH_SIZE = 100;

    private final FileCleanupRepository repository;
    private final Path rootDirectory;
    private final LogSyncProperties syncProperties;
    private final boolean enabled;
    private final int maximumRetryCount;
    private final int retryDelayMinutes;

    public FileCleanupService(FileCleanupRepository cleanupRepository, String root,
            LogSyncProperties logSyncProperties, boolean cleanupEnabled,
            int maxRetryCount, int delayMinutes) {
        if (logSyncProperties == null) {
            throw new IllegalArgumentException("logSyncProperties must not be null");
        }
        if (maxRetryCount < 1) {
            throw new IllegalArgumentException("maxRetryCount must be greater than zero");
        }
        if (delayMinutes < 1) {
            throw new IllegalArgumentException("retryDelayMinutes must be greater than zero");
        }
        repository = cleanupRepository;
        rootDirectory = Paths.get(root).toAbsolutePath().normalize();
        syncProperties = logSyncProperties;
        enabled = cleanupEnabled;
        maximumRetryCount = maxRetryCount;
        retryDelayMinutes = delayMinutes;
    }

    /**
     * 解析成功后立即尝试清理指定文件。
     *
     * @param fileRecordId 文件记录 ID
     * @return 本次是否实际领取了清理任务
     */
    public boolean cleanupAfterAnalysis(long fileRecordId) {
        if (!isCleanupEnabled()) {
            return false;
        }
        ClaimedCleanup cleanup = repository.claimByFileRecordId(fileRecordId);
        if (cleanup == null) {
            return false;
        }
        execute(cleanup);
        return true;
    }

    /**
     * 执行补偿清理，扫描已解析成功但尚未完成删除的任务。
     *
     * @param maximumFiles 本次最多处理文件数
     * @return 执行结果汇总
     */
    public CleanupResult cleanupPending(int maximumFiles) {
        if (!isCleanupEnabled()) {
            return new CleanupResult(0, 0);
        }
        if (maximumFiles < 1 || maximumFiles > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("maximumFiles must be between 1 and 100");
        }
        int success = 0;
        int failure = 0;
        for (int index = 0; index < maximumFiles; index++) {
            try {
                ClaimedCleanup cleanup = repository.claimNext();
                if (cleanup == null) {
                    break;
                }
                if (execute(cleanup)) {
                    success++;
                } else {
                    failure++;
                }
            } catch (RuntimeException ex) {
                failure++;
                log.error("event=file_cleanup_batch_item_failed 文件清理异常，继续处理剩余批次：batchIndex={}",
                        Integer.valueOf(index), ex);
            }
        }
        return new CleanupResult(success, failure);
    }

    /** 日志同步生命周期与文件清理能力必须同时启用。 */
    private boolean isCleanupEnabled() {
        return syncProperties.isEnabled() && enabled;
    }

    private boolean execute(ClaimedCleanup cleanup) {
        try {
            Path file = validate(cleanup.getLocalPath());
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                repository.markDeleted(cleanup.getCleanupId(), "File already absent");
                return true;
            }
            Files.delete(file);
            repository.markDeleted(cleanup.getCleanupId(), "File deleted");
            return true;
        } catch (NoSuchFileException ex) {
            repository.markDeleted(cleanup.getCleanupId(), "File already absent");
            return true;
        } catch (UnsafeCleanupPathException ex) {
            repository.markManualRequired(cleanup.getCleanupId(), cleanup.getRetryCount(),
                    limit(ex.getMessage()));
            return false;
        } catch (IOException ex) {
            handleIoFailure(cleanup, ex);
            return false;
        }
    }

    private Path validate(String localPath) throws IOException {
        Path candidate = Paths.get(localPath).toAbsolutePath().normalize();
        if (!candidate.startsWith(rootDirectory)) {
            throw new UnsafeCleanupPathException("Cleanup path is outside configured root directory");
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return candidate;
        }
        if (Files.isSymbolicLink(candidate)) {
            throw new UnsafeCleanupPathException("Symbolic link cleanup is not allowed");
        }
        Path realRoot = rootDirectory.toRealPath();
        Path realFile = candidate.toRealPath();
        if (!realFile.startsWith(realRoot)) {
            throw new UnsafeCleanupPathException("Resolved cleanup path is outside configured root directory");
        }
        if (!Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnsafeCleanupPathException("Cleanup target is not a regular file");
        }
        return realFile;
    }

    private void handleIoFailure(ClaimedCleanup cleanup, IOException exception) {
        int retryCount = cleanup.getRetryCount() + 1;
        String message = limit(exception.getMessage());
        if (retryCount >= maximumRetryCount) {
            message = limit("Automatic retry threshold reached; manual intervention required. "
                    + message);
        }
        repository.markRetry(cleanup.getCleanupId(), retryCount,
                LocalDateTime.now().plusMinutes(retryDelayMinutes), message);
    }

    private static String limit(String message) {
        String value = message == null ? "Unknown file cleanup failure" : message;
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    /** 单次补偿清理结果。 */
    public static final class CleanupResult {
        private final int candidateCount;
        private final int successCount;
        private final int failureCount;

        public CleanupResult(int success, int failure) {
            candidateCount = success + failure;
            successCount = success;
            failureCount = failure;
        }

        public int getCandidateCount() {
            return candidateCount;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getFailureCount() {
            return failureCount;
        }
    }

    /** 表示路径越界或目标类型不允许自动删除。 */
    private static final class UnsafeCleanupPathException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private UnsafeCleanupPathException(String message) {
            super(message);
        }
    }
}
