package io.github.badfisher.ailog.application.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.badfisher.ailog.application.cleanup.FileCleanupService.CleanupResult;
import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.domain.cleanup.FileCleanupRepository;

class FileCleanupServiceTest {

    @TempDir
    Path root;

    @Test
    void deletesRegularFileInsideConfiguredRoot() throws Exception {
        Path file = root.resolve("ready/error/test.log");
        Files.createDirectories(file.getParent());
        Files.write(file, "error".getBytes(StandardCharsets.UTF_8));
        RecordingRepository repository = new RecordingRepository(file);
        FileCleanupService service = new FileCleanupService(repository, root.toString(),
                syncProperties(true), true, 3, 30);

        boolean claimed = service.cleanupAfterAnalysis(2L);

        assertThat(claimed).isTrue();
        assertThat(Files.exists(file)).isFalse();
        assertThat(repository.deleted).isTrue();
        assertThat(repository.manualRequired).isFalse();
    }

    @Test
    void rejectsFileOutsideConfiguredRoot() throws Exception {
        Path outside = root.getParent().resolve(root.getFileName() + "-outside.log");
        Files.write(outside, "error".getBytes(StandardCharsets.UTF_8));
        RecordingRepository repository = new RecordingRepository(outside);
        FileCleanupService service = new FileCleanupService(repository, root.toString(),
                syncProperties(true), true, 3, 30);

        boolean claimed = service.cleanupAfterAnalysis(2L);

        assertThat(claimed).isTrue();
        assertThat(Files.exists(outside)).isTrue();
        assertThat(repository.deleted).isFalse();
        assertThat(repository.manualRequired).isTrue();
        Files.deleteIfExists(outside);
    }

    @Test
    void disabledSyncLifecyclePreventsEveryCleanupEntryPoint() throws Exception {
        Path file = root.resolve("ready/error/test.log");
        Files.createDirectories(file.getParent());
        byte[] original = "error".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        RecordingRepository repository = new RecordingRepository(file);
        FileCleanupService service = new FileCleanupService(repository, root.toString(),
                syncProperties(false), true, 3, 30);

        boolean claimed = service.cleanupAfterAnalysis(2L);
        CleanupResult pending = service.cleanupPending(1);

        assertThat(claimed).isFalse();
        assertThat(pending.getCandidateCount()).isZero();
        assertThat(pending.getSuccessCount()).isZero();
        assertThat(pending.getFailureCount()).isZero();
        assertThat(repository.claimed).isFalse();
        assertThat(repository.deleted).isFalse();
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
    }

    private static LogSyncProperties syncProperties(boolean enabled) {
        LogSyncProperties properties = new LogSyncProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private static final class RecordingRepository implements FileCleanupRepository {
        private final ClaimedCleanup cleanup;
        private boolean claimed;
        private boolean deleted;
        private boolean manualRequired;

        private RecordingRepository(Path file) {
            cleanup = new ClaimedCleanup(1L, 2L, file.toString(), 0);
        }

        @Override
        public ClaimedCleanup claimByFileRecordId(long fileRecordId) {
            if (claimed) {
                return null;
            }
            claimed = true;
            return cleanup;
        }

        @Override
        public ClaimedCleanup claimNext() {
            return claimByFileRecordId(cleanup.getFileRecordId());
        }

        @Override
        public void markDeleted(long cleanupId, String remark) {
            deleted = true;
        }

        @Override
        public void markRetry(long cleanupId, int retryCount, LocalDateTime nextRetryTime,
                String errorMessage) {
        }

        @Override
        public void markManualRequired(long cleanupId, int retryCount, String errorMessage) {
            manualRequired = true;
        }

    }


}
