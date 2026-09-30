package io.github.badfisher.ailog.ingestion.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.sync.LogChannel;
import io.github.badfisher.ailog.domain.sync.LogSyncItem;
import io.github.badfisher.ailog.domain.sync.LogSyncPlan;
import io.github.badfisher.ailog.domain.sync.ResolvedRemoteFile;

class ShellRemoteFileResolverTest {
    @Test
    void prefersPlainLogWhenBothCandidatesCouldExist() {
        SequenceExecutor executor = new SequenceExecutor(0);
        ResolvedRemoteFile resolved = resolver(executor).resolve(plan(), item());
        assertThat(resolved.getFileName()).isEqualTo("20260816_badfisher-sample-service-err.log");
        assertThat(executor.calls).isEqualTo(1);
    }

    @Test
    void fallsBackToGzip() {
        SequenceExecutor executor = new SequenceExecutor(1, 0);
        ResolvedRemoteFile resolved = resolver(executor).resolve(plan(), item());
        assertThat(resolved.getFileName()).endsWith(".log.gz");
        assertThat(resolved.isCompressed()).isTrue();
    }

    @Test
    void reportsCandidatesWhenNoRemoteFileExists() {
        assertThatThrownBy(() -> resolver(new SequenceExecutor(1, 1)).resolve(plan(), item()))
                .isInstanceOfSatisfying(LogSyncException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(SyncErrorCode.REMOTE_FILE_NOT_FOUND))
                .hasMessageContaining("sample-service", "2026-08-16", "ERROR", ".log.gz");
    }

    private static ShellRemoteFileResolver resolver(CommandExecutor executor) {
        return new ShellRemoteFileResolver(executor,
                new ShellScriptCommandBuilder("/opt/badfisher-ai-log/bin", ""), Duration.ofSeconds(30L));
    }

    private static LogSyncPlan plan() {
        return new LogSyncPlan("prod", "demo", "sample-service", LocalDate.of(2026, 8, 16),
                "log.example", 22, "op_read", null, "JAVA", "default",
                Collections.singletonList(item()));
    }

    private static LogSyncItem item() {
        return new LogSyncItem(LogChannel.ERROR, "/data/log/sample-service",
                Arrays.asList("20260816_badfisher-sample-service-err.log", "20260816_badfisher-sample-service-err.log.gz"),
                Paths.get("incoming"), Paths.get("ready"), true);
    }

    private static final class SequenceExecutor implements CommandExecutor {
        private final int[] exits;
        private int calls;
        private SequenceExecutor(int... values) { exits = values; }
        @Override
        public CommandResult execute(List<String> command, Duration timeout) {
            return new CommandResult(exits[calls++], "");
        }
    }
}
