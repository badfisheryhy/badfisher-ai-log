package io.github.badfisher.ailog.application.path;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogPathAccessPolicyTest {

    @TempDir
    Path directory;

    @Test
    void acceptsExistingDirectoryAndRegularFileInsideConfiguredRoot() throws Exception {
        Path root = Files.createDirectory(directory.resolve("logs"));
        Path module = Files.createDirectory(root.resolve("module"));
        Path file = Files.writeString(module.resolve("error.log"), "test");
        List<String> roots = List.of(root.toString());

        assertThat(LogPathAccessPolicy.requireDirectory(module, roots)).isEqualTo(module.toRealPath());
        assertThat(LogPathAccessPolicy.requireFile(file, roots)).isEqualTo(file.toRealPath());
    }

    @Test
    void rejectsEmptyPolicyAndFilesystemRoot() {
        assertThatThrownBy(() -> LogPathAccessPolicy.requireDirectory(directory, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowed-log-roots");
        assertThatThrownBy(() -> LogPathAccessPolicy.requireDirectory(directory,
                List.of(directory.getRoot().toString())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("文件系统根");
    }

    @Test
    void rejectsSiblingPrefixAndTraversalOutsideRoot() throws Exception {
        Path root = Files.createDirectory(directory.resolve("logs"));
        Path sibling = Files.createDirectory(directory.resolve("logs-private"));
        List<String> roots = List.of(root.toString());

        assertThatThrownBy(() -> LogPathAccessPolicy.requireDirectory(sibling, roots))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogPathAccessPolicy.requireDirectory(
                root.resolve("..").resolve("logs-private"), roots))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRelativeMissingAndDirectoryAsFile() {
        List<String> roots = List.of(directory.toString());
        assertThatThrownBy(() -> LogPathAccessPolicy.requireDirectory(Path.of("."), roots))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogPathAccessPolicy.requireFile(directory.resolve("missing.log"), roots))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogPathAccessPolicy.requireFile(directory, roots))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
