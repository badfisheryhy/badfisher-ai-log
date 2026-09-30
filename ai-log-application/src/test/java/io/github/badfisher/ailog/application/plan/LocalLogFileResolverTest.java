package io.github.badfisher.ailog.application.plan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalLogFileResolverTest {

    @TempDir
    Path directory;

    @Test
    void matchesRotationAndArbitraryNamesWithoutDuplicatingPlainAndGzip() throws Exception {
        Files.writeString(directory.resolve("orders-error.log"), "");
        Files.writeString(directory.resolve("orders-error.log.gz"), "");
        Files.writeString(directory.resolve("orders-error.log.1"), "");
        Files.writeString(directory.resolve("info.log"), "");
        assertThat(LocalLogFileResolver.resolve(directory, List.of("*error*.log*"),
                LocalDate.of(2026, 9, 27), "orders", 10))
                .extracting(path -> path.getFileName().toString())
                .containsExactly("orders-error.log", "orders-error.log.1");
    }

    @Test
    void supportsDateTemplatesAndRejectsTraversalOrUnboundedMatches() throws Exception {
        Files.writeString(directory.resolve("custom-20260927.txt"), "");
        assertThat(LocalLogFileResolver.resolve(directory, List.of("custom-{compactDate}.txt"),
                LocalDate.of(2026, 9, 27), "", 10)).hasSize(1);
        assertThatThrownBy(() -> LocalLogFileResolver.resolve(directory, List.of("../*.log"),
                LocalDate.now(), "", 10)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(directory.resolve("other.txt"), "");
        assertThatThrownBy(() -> LocalLogFileResolver.resolve(directory, List.of("*.txt"),
                LocalDate.now(), "", 1)).isInstanceOf(IllegalStateException.class);
    }
}