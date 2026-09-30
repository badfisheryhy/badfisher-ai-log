package io.github.badfisher.ailog.ingestion.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import io.github.badfisher.ailog.domain.sync.LogChannel;
import io.github.badfisher.ailog.domain.sync.LogSyncItem;
import io.github.badfisher.ailog.domain.sync.LogSyncPlan;

class PlannedLogSyncServiceTest {
    @TempDir Path root;

    @Test
    void publishesSuccessfulEmptyLogAndReusesReadyFile() throws Exception {
        WritingExecutor executor = new WritingExecutor();
        ShellScriptCommandBuilder commands = new ShellScriptCommandBuilder("/opt/bin", "");
        PlannedLogSyncService service = new PlannedLogSyncService(
                new ShellRemoteFileResolver(executor, commands, Duration.ofSeconds(30L)),
                executor, commands, new SyncVerifier(), Duration.ofSeconds(30L));

        ModuleLogSyncResult first = service.sync(plan(), false);
        ModuleLogSyncResult second = service.sync(plan(), false);

        assertThat(first.getFiles().get(0).getReadyFile()).isRegularFile();
        assertThat(first.getFiles().get(0).getFileSize()).isZero();
        assertThat(second.getFiles().get(0).isSkipped()).isTrue();
        assertThat(executor.rsyncCalls).isEqualTo(1);
    }

    @Test
    void executorRejectionStillWaitsForSubmittedTaskAndPreservesListenerFailure()
            throws Exception {
        WritingExecutor commandExecutor = new WritingExecutor();
        ShellScriptCommandBuilder commands = new ShellScriptCommandBuilder("/opt/bin", "");
        AtomicInteger submissions = new AtomicInteger();
        CountDownLatch submittedTaskFinished = new CountDownLatch(1);
        RejectedExecutionException rejection =
                new RejectedExecutionException("文件线程池拒绝第二个任务");
        IllegalStateException listenerFailure = new IllegalStateException("失败监听器写库失败");
        Executor fileExecutor = command -> {
            if (submissions.incrementAndGet() == 2) {
                throw rejection;
            }
            Thread thread = new Thread(() -> {
                command.run();
                submittedTaskFinished.countDown();
            });
            thread.start();
        };
        PlannedLogSyncService service = new PlannedLogSyncService(
                new ShellRemoteFileResolver(commandExecutor, commands, Duration.ofSeconds(30L)),
                commandExecutor, commands, new SyncVerifier(), Duration.ofSeconds(30L),
                2, fileExecutor);
        LogSyncProgressListener listener = new LogSyncProgressListener() {
            @Override
            public void syncing(LogChannel channel) {
            }

            @Override
            public void ready(LogSyncFileResult result) {
            }

            @Override
            public void failed(LogChannel channel, String errorMessage) {
                throw listenerFailure;
            }
        };

        assertThatThrownBy(() -> service.sync(twoChannelPlan(), false, listener))
                .isSameAs(rejection)
                .satisfies(error -> assertThat(error.getSuppressed())
                        .contains(listenerFailure));
        assertThat(submittedTaskFinished.await(1L, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void listenerFailureDoesNotReplaceOriginalSyncFailure() {
        ShellScriptCommandBuilder commands = new ShellScriptCommandBuilder("/opt/bin", "");
        CommandExecutor missing = (command, timeout) -> new CommandResult(1, "");
        PlannedLogSyncService service = new PlannedLogSyncService(
                new ShellRemoteFileResolver(missing, commands, Duration.ofSeconds(30L)),
                missing, commands, new SyncVerifier(), Duration.ofSeconds(30L));
        IllegalStateException listenerFailure = new IllegalStateException("失败监听器写库失败");
        LogSyncProgressListener listener = new LogSyncProgressListener() {
            @Override
            public void syncing(LogChannel channel) {
            }

            @Override
            public void ready(LogSyncFileResult result) {
            }

            @Override
            public void failed(LogChannel channel, String errorMessage) {
                throw listenerFailure;
            }
        };

        assertThatThrownBy(() -> service.sync(plan(), false, listener))
                .isInstanceOfSatisfying(LogSyncException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(
                            SyncErrorCode.REMOTE_FILE_NOT_FOUND);
                    assertThat(error.getSuppressed()).contains(listenerFailure);
                });
    }

    @Test
    void rejectsReadyDirectoryWhoseParentPathContainsSymbolicLink() throws Exception {
        Path realReady = Files.createDirectories(root.resolve("real-ready"));
        Path linkedReady = root.resolve("linked-ready");
        try {
            Files.createSymbolicLink(linkedReady, realReady);
        } catch (Exception exception) {
            Assumptions.assumeTrue(false,
                    "当前Windows环境无创建软链接权限，Linux部署仍需执行该边界测试："
                            + exception.getMessage());
        }
        LogSyncItem item = new LogSyncItem(LogChannel.ERROR, "/remote",
                Collections.singletonList("error.log"), root.resolve("incoming/error"),
                linkedReady.resolve("error"), true);
        LogSyncPlan plan = new LogSyncPlan("test", "demo", "sample-service",
                LocalDate.of(2026, 8, 16), "log.example", 22, "op_read", null,
                "JAVA", "default", Collections.singletonList(item));
        WritingExecutor executor = new WritingExecutor();

        assertThatThrownBy(() -> service(executor).sync(plan, false))
                .isInstanceOfSatisfying(LogSyncException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(SyncErrorCode.LOCAL_SYMLINK_REJECTED));
        assertThat(executor.rsyncCalls).isZero();
    }

    private LogSyncPlan plan() {
        LogSyncItem item = new LogSyncItem(LogChannel.ERROR, "/remote",
                Arrays.asList("20260816_badfisher-sample-service-err.log", "20260816_badfisher-sample-service-err.log.gz"),
                root.resolve("incoming/error"), root.resolve("ready/error"), true);
        return new LogSyncPlan("prod", "demo", "sample-service", LocalDate.of(2026, 8, 16),
                "log.example", 22, "op_read", null, "JAVA", "default",
                Collections.singletonList(item));
    }

    private LogSyncPlan twoChannelPlan() {
        LogSyncItem error = new LogSyncItem(LogChannel.ERROR, "/remote",
                Collections.singletonList("error.log"), root.resolve("incoming/error"),
                root.resolve("ready/error"), true);
        LogSyncItem application = new LogSyncItem(LogChannel.APPLICATION, "/remote",
                Collections.singletonList("application.log"),
                root.resolve("incoming/application"), root.resolve("ready/application"), true);
        return new LogSyncPlan("test", "demo", "sample-service", LocalDate.of(2026, 8, 16),
                "log.example", 22, "op_read", null, "JAVA", "default",
                Arrays.asList(error, application));
    }

    private PlannedLogSyncService service(CommandExecutor executor) {
        ShellScriptCommandBuilder commands = new ShellScriptCommandBuilder("/opt/bin", "");
        return new PlannedLogSyncService(
                new ShellRemoteFileResolver(executor, commands, Duration.ofSeconds(30L)),
                executor, commands, new SyncVerifier(), Duration.ofSeconds(30L));
    }

    private static final class WritingExecutor implements CommandExecutor {
        private int rsyncCalls;
        @Override
        public CommandResult execute(List<String> command, Duration timeout) {
            try {
                if (command.get(0).endsWith("check-remote-file.sh")) { return new CommandResult(0, ""); }
                rsyncCalls++;
                String remote = command.get(4);
                String fileName = remote.substring(remote.lastIndexOf('/') + 1);
                Path target = java.nio.file.Paths.get(command.get(5));
                Files.createDirectories(target);
                Files.write(target.resolve(fileName), new byte[0]);
                return new CommandResult(0, "");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
