package io.github.badfisher.ailog.ingestion.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** 系统命令超时收尾测试。 */
class SystemCommandExecutorTest {

    @Test
    void timeoutTerminatesProcessWithoutUnboundedCollectorWait() {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        Instant startedAt = Instant.now();

        assertThatThrownBy(() -> new SystemCommandExecutor().execute(
                Arrays.asList(java, "-cp", classpath, SleepingProcess.class.getName()),
                Duration.ofMillis(100L)))
                        .isInstanceOfSatisfying(LogSyncException.class,
                                ex -> assertThat(ex.getErrorCode()).isEqualTo(SyncErrorCode.SYNC_TIMEOUT));

        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(12L));
    }

    @Test
    void rejectsNonPositiveTimeoutBeforeStartingProcess() {
        assertThatThrownBy(() -> new SystemCommandExecutor().execute(
                Arrays.asList("unused"), Duration.ZERO))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("命令超时时间必须为正数");
    }

    @Test
    void capsBothOutputStreamsEvenWhenAProcessPrintsOneVeryLongLine() {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        CommandResult result = new SystemCommandExecutor().execute(
                Arrays.asList(java, "-cp", classpath, VerboseProcess.class.getName()),
                Duration.ofSeconds(10L));

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).matches("o{8192}");
        assertThat(result.getStderr()).matches("e{8192}");
    }

    /** 输出超过管道及采集上限，确保截断后仍持续排空两个流。 */
    public static final class VerboseProcess {
        private VerboseProcess() {
        }

        public static void main(String[] args) {
            char[] output = new char[4096];
            char[] error = new char[4096];
            Arrays.fill(output, 'o');
            Arrays.fill(error, 'e');
            String outputChunk = new String(output);
            String errorChunk = new String(error);
            for (int index = 0; index < 256; index++) {
                System.out.print(outputChunk);
                System.err.print(errorChunk);
            }
            System.out.flush();
            System.err.flush();
        }
    }

    /** 供测试启动的长时间运行子进程。 */
    public static final class SleepingProcess {
        private SleepingProcess() {
        }

        public static void main(String[] args) throws Exception {
            Thread.sleep(60000L);
        }
    }
}
