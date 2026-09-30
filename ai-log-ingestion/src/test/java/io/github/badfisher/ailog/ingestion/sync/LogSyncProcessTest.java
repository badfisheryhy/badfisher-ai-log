package io.github.badfisher.ailog.ingestion.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.badfisher.ailog.domain.sync.LogChannel;
import io.github.badfisher.ailog.domain.sync.LogSyncItem;
import io.github.badfisher.ailog.domain.sync.LogSyncPlan;

/** Step 2：远程选择、rsync、incoming 校验和 ready 发布全链路测试。 */
class LogSyncProcessTest {
    @TempDir Path root;

    @Test
    void selectsGzipFallbackAndRecordsActualRemotePath() {
        ScenarioExecutor executor = new ScenarioExecutor(true, false);
        LogSyncFileResult file = service(executor).sync(plan(), false).getFiles().get(0);
        assertThat(file.getRemotePath()).endsWith("sample-service-err.log.gz");
        assertThat(file.getReadyFile()).isRegularFile();
        assertThat(file.getReadyFile().getFileName().toString()).endsWith(".gz");
    }

    @Test
    void failedRsyncDoesNotPublishReadyFile() {
        ScenarioExecutor executor = new ScenarioExecutor(false, true);
        assertThatThrownBy(() -> service(executor).sync(plan(), false))
                .isInstanceOf(LogSyncException.class)
                .satisfies(error -> assertThat(((LogSyncException) error).getErrorCode())
                        .isEqualTo(SyncErrorCode.RSYNC_FAILED));
        assertThat(root.resolve("ready/error/20260816_badfisher-sample-service-err.log")).doesNotExist();
    }

    @Test
    void existingReadyFileIsIdempotentlySkipped() throws Exception {
        Path ready = root.resolve("ready/error/20260816_badfisher-sample-service-err.log");
        Files.createDirectories(ready.getParent());
        Files.write(ready, "existing".getBytes(StandardCharsets.UTF_8));
        ScenarioExecutor executor = new ScenarioExecutor(false, false);
        LogSyncFileResult file = service(executor).sync(plan(), false).getFiles().get(0);
        assertThat(file.isSkipped()).isTrue();
        assertThat(executor.rsyncCalls).isZero();
    }

    @Test
    void synchronizesFilesWithinModuleUsingBoundedConcurrency() {
        ConcurrentExecutor executor = new ConcurrentExecutor();
        ShellScriptCommandBuilder commands = new ShellScriptCommandBuilder("/opt/bin", "");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            PlannedLogSyncService service = new PlannedLogSyncService(
                    new ShellRemoteFileResolver(executor, commands, Duration.ofSeconds(5L)),
                    executor, commands, new SyncVerifier(), Duration.ofSeconds(5L), 2, pool);
            ModuleLogSyncResult result = service.sync(twoFilePlan(), false);
            assertThat(result.getFiles()).hasSize(2);
            assertThat(executor.concurrentRsyncObserved).isTrue();
            assertThat(result.getFiles()).extracting(LogSyncFileResult::getChannel)
                    .containsExactly(LogChannel.ERROR, LogChannel.APPLICATION);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sequentialModeAttemptsRemainingChannelsAfterOneFails() {
        PartialFailureExecutor executor = new PartialFailureExecutor();
        ShellScriptCommandBuilder commands = new ShellScriptCommandBuilder("/opt/bin", "");
        PlannedLogSyncService service = new PlannedLogSyncService(
                new ShellRemoteFileResolver(executor, commands, Duration.ofSeconds(5L)),
                executor, commands, new SyncVerifier(), Duration.ofSeconds(5L));

        assertThatThrownBy(() -> service.sync(twoFilePlan(), false))
                .isInstanceOfSatisfying(LogSyncException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(SyncErrorCode.RSYNC_FAILED));

        assertThat(executor.rsyncCalls).isEqualTo(2);
        assertThat(root.resolve("ready/application/20260816_badfisher-sample-service-all.log")).isRegularFile();
    }

    @Test
    void rejectsForceBeforeCallingRemoteOrMovingReadyFile() throws Exception {
        Path ready = root.resolve("ready/error/20260816_badfisher-sample-service-err.log");
        Files.createDirectories(ready.getParent());
        Files.write(ready, "保留原文件".getBytes(StandardCharsets.UTF_8));
        ScenarioExecutor executor = new ScenarioExecutor(false, false);

        assertThatThrownBy(() -> service(executor).sync(plan(), true))
                .isInstanceOfSatisfying(LogSyncException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(SyncErrorCode.FORCE_DISABLED));

        assertThat(new String(Files.readAllBytes(ready), StandardCharsets.UTF_8)).isEqualTo("保留原文件");
        assertThat(executor.checks).isZero();
        assertThat(executor.rsyncCalls).isZero();
    }

    @Test
    void preservesRemoteSymlinkErrorInsteadOfFallingBackToGzipOrGenericRsyncError() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        CommandExecutor executor = (command, timeout) -> {
            calls.incrementAndGet();
            return new CommandResult(10, "");
        };

        assertThatThrownBy(() -> service(executor).sync(plan(), false))
                .isInstanceOfSatisfying(LogSyncException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(SyncErrorCode.REMOTE_SYMLINK_REJECTED));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void preservesMissingFileErrorAfterAllCandidatesAreChecked() {
        CommandExecutor executor = (command, timeout) -> new CommandResult(1, "");

        assertThatThrownBy(() -> service(executor).sync(plan(), false))
                .isInstanceOfSatisfying(LogSyncException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(SyncErrorCode.REMOTE_FILE_NOT_FOUND));
    }

    private PlannedLogSyncService service(CommandExecutor executor) {
        ShellScriptCommandBuilder commands = new ShellScriptCommandBuilder("/opt/bin", "");
        return new PlannedLogSyncService(
                new ShellRemoteFileResolver(executor, commands, Duration.ofSeconds(5L)),
                executor, commands, new SyncVerifier(), Duration.ofSeconds(5L));
    }

    private LogSyncPlan plan() {
        LogSyncItem item = new LogSyncItem(LogChannel.ERROR, "/remote",
                Arrays.asList("20260816_badfisher-sample-service-err.log", "20260816_badfisher-sample-service-err.log.gz"),
                root.resolve("incoming/error"), root.resolve("ready/error"), true);
        return new LogSyncPlan("prod", "demo", "sample-service", LocalDate.of(2026, 8, 16),
                "log.example", 22, "op_read", null, "JAVA", "default",
                Collections.singletonList(item));
    }

    private LogSyncPlan twoFilePlan() {
        LogSyncItem error = new LogSyncItem(LogChannel.ERROR, "/remote",
                Collections.singletonList("20260816_badfisher-sample-service-err.log"),
                root.resolve("incoming/error"), root.resolve("ready/error"), true);
        LogSyncItem application = new LogSyncItem(LogChannel.APPLICATION, "/remote",
                Collections.singletonList("20260816_badfisher-sample-service-all.log"),
                root.resolve("incoming/application"), root.resolve("ready/application"), true);
        return new LogSyncPlan("prod", "demo", "sample-service", LocalDate.of(2026, 8, 16),
                "log.example", 22, "op_read", null, "JAVA", "default",
                Arrays.asList(error, application));
    }

    private static final class ScenarioExecutor implements CommandExecutor {
        private final boolean gzipOnly;
        private final boolean failRsync;
        private int checks;
        private int rsyncCalls;
        private ScenarioExecutor(boolean useGzipOnly, boolean fail) {
            gzipOnly = useGzipOnly;
            failRsync = fail;
        }
        @Override
        public CommandResult execute(List<String> command, Duration timeout) {
            try {
                if (command.get(0).endsWith("check-remote-file.sh")) {
                    checks++;
                    return new CommandResult(gzipOnly && checks == 1 ? 1 : 0, "");
                }
                rsyncCalls++;
                if (failRsync) { return new CommandResult(12, "rsync failed"); }
                String remote = command.get(4);
                Path target = java.nio.file.Paths.get(command.get(5));
                Files.createDirectories(target);
                Files.write(target.resolve(remote.substring(remote.lastIndexOf('/') + 1)), new byte[0]);
                return new CommandResult(0, "");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static final class ConcurrentExecutor implements CommandExecutor {
        private final CountDownLatch rsyncStarted = new CountDownLatch(2);
        private volatile boolean concurrentRsyncObserved;
        @Override
        public CommandResult execute(List<String> command, Duration timeout) {
            try {
                if (command.get(0).endsWith("check-remote-file.sh")) { return new CommandResult(0, ""); }
                rsyncStarted.countDown();
                concurrentRsyncObserved = rsyncStarted.await(2L, TimeUnit.SECONDS);
                String remote = command.get(4);
                Path target = java.nio.file.Paths.get(command.get(5));
                Files.createDirectories(target);
                Files.write(target.resolve(remote.substring(remote.lastIndexOf('/') + 1)), new byte[0]);
                return new CommandResult(0, "");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static final class PartialFailureExecutor implements CommandExecutor {
        private int rsyncCalls;

        @Override
        public CommandResult execute(List<String> command, Duration timeout) {
            try {
                if (command.get(0).endsWith("check-remote-file.sh")) {
                    return new CommandResult(0, "");
                }
                rsyncCalls++;
                String remote = command.get(4);
                if (remote.endsWith("-err.log")) {
                    return new CommandResult(12, "rsync failed");
                }
                Path target = java.nio.file.Paths.get(command.get(5));
                Files.createDirectories(target);
                Files.write(target.resolve(remote.substring(remote.lastIndexOf('/') + 1)), new byte[0]);
                return new CommandResult(0, "");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
