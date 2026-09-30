package io.github.badfisher.ailog.ingestion.sync;

import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/** 通过参数数组执行命令，不经过 Shell 解释。 */
@Slf4j
public final class SystemCommandExecutor implements CommandExecutor {

    /** 输出采集最大字符数，超出部分丢弃。 */
    private static final int MAX_OUTPUT_CHARS = 8192;
    /** 超时后等待进程退出的最大时间。 */
    private static final long PROCESS_TERMINATION_TIMEOUT_SECONDS = 5L;
    /** 等待输出采集线程结束的最大时间。 */
    private static final long OUTPUT_DRAIN_TIMEOUT_SECONDS = 5L;

    @Override
    public CommandResult execute(List<String> command, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("命令超时时间必须为正数");
        }
        Process process = null;
        Instant startedAt = Instant.now();
        try {
            process = new ProcessBuilder(command).start();
            OutputCollector stdout = new OutputCollector(process.getInputStream());
            OutputCollector stderr = new OutputCollector(process.getErrorStream());
            stdout.start();
            stderr.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                closeProcessStreams(process);
                awaitCollectors(process, stdout, stderr);
                throw new LogSyncException(SyncErrorCode.SYNC_TIMEOUT, "命令执行超时");
            }
            awaitCollectors(process, stdout, stderr);
            return new CommandResult(process.exitValue(), stdout.getOutput(), stderr.getOutput(),
                    startedAt, Instant.now(), false);
        } catch (LogSyncException ex) {
            // 命令失败（如超时）已归类错误码，记录事件日志后原样上抛，保留阶段错误码
            log.error("event=command_execution_failed 命令执行失败：command={}, errorCode={}, durationMs={}",
                    command, ex.getErrorCode(), Duration.between(startedAt, Instant.now()).toMillis(), ex);
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("event=command_execution_interrupted 命令执行被中断：command={}", command, ex);
            throw new LogSyncException("命令执行被中断", ex);
        } catch (Exception ex) {
            log.error("event=command_execution_failed 命令执行失败：command={}", command, ex);
            throw new LogSyncException("命令执行失败，command=" + command, ex);
        } finally {
            if (process != null) {
                closeProcessStreams(process);
                process.destroy();
            }
        }
    }

    /** 有界终止超时进程，确认强制终止后不再无限等待。 */
    private static void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (process.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return;
        }
        process.destroyForcibly();
        process.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** 关闭进程管道，使派生进程占用管道时采集线程也能退出。 */
    private static void closeProcessStreams(Process process) {
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
    }

    /** 有界等待输出采集；派生进程保持管道时主动关闭并再次有界等待。 */
    private static void awaitCollectors(Process process, OutputCollector stdout,
            OutputCollector stderr) throws InterruptedException {
        boolean stdoutCompleted = stdout.await(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        boolean stderrCompleted = stderr.await(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!stdoutCompleted || !stderrCompleted) {
            closeProcessStreams(process);
            stdout.await(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stderr.await(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 超时收尾阶段只保证不再阻塞，原始超时仍是主失败原因。
        }
    }

    /** 后台线程采集进程输出，避免管道阻塞。 */
    private static final class OutputCollector {
        private final InputStream input;
        private final StringBuilder output = new StringBuilder();
        private final CountDownLatch completed = new CountDownLatch(1);

        private OutputCollector(InputStream stream) {
            input = stream;
        }

        private void start() {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
                        char[] buffer = new char[2048];
                        int count;
                        while ((count = reader.read(buffer)) != -1) {
                            // 达到上限后继续排空管道，避免子进程因输出阻塞；不再构建整条长行。
                            synchronized (output) {
                                int remaining = MAX_OUTPUT_CHARS - output.length();
                                if (remaining > 0) {
                                    output.append(buffer, 0, Math.min(count, remaining));
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // 命令退出时流可能被系统关闭，退出码仍是主判断依据。
                    } finally {
                        completed.countDown();
                    }
                }
            }, "command-output-collector");
            thread.setDaemon(true);
            thread.start();
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return completed.await(timeout, unit);
        }

        private String getOutput() {
            synchronized (output) {
                return output.toString();
            }
        }
    }
}
