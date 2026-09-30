package io.github.badfisher.ailog.bootstrap.integration.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.badfisher.ailog.bootstrap.integration.script.BundledScriptInstaller;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.ingestion.sync.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GitRepositorySyncServiceTest {

    @TempDir
    Path directory;

    @Test
    void rejectsHttpBeforeCreatingWorkspaceOrRunningCommand() {
        GitSyncProperties properties = properties();
        CommandExecutor executor = mock(CommandExecutor.class);
        GitRepositorySyncService service = service(properties, executor);

        assertThatThrownBy(() -> service.syncLatest(module("http://example.invalid/repo.git")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allow-insecure-http");
        assertThat(Files.exists(Path.of(properties.getWorkspace()))).isFalse();
        verifyNoInteractions(executor);
    }

    @Test
    void forwardsExplicitHttpOptInToScript() {
        assertCommandPolicy("http://example.invalid/repo.git", true);
    }

    @Test
    void httpsUsesSecureDefaultAtScriptBoundary() {
        assertCommandPolicy("https://example.invalid/repo.git", false);
    }

    @Test
    void insecureFlagDoesNotBypassCredentialHostAllowlist() {
        GitSyncProperties properties = properties();
        properties.setAllowInsecureHttp(true);
        properties.setUsername("test-user");
        properties.setPassword("synthetic-token");
        properties.setAllowedHosts(List.of("trusted.invalid"));
        CommandExecutor executor = mock(CommandExecutor.class);

        assertThatThrownBy(() -> service(properties, executor)
                .syncLatest(module("http://example.invalid/repo.git")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowed-hosts");
        verifyNoInteractions(executor);
    }

    private void assertCommandPolicy(String url, boolean allowInsecureHttp) {
        GitSyncProperties properties = properties();
        properties.setAllowInsecureHttp(allowInsecureHttp);
        CommandExecutor executor = mock(CommandExecutor.class);
        when(executor.execute(any(), any())).thenThrow(new IllegalStateException("test stop before network"));

        assertThatThrownBy(() -> service(properties, executor).syncLatest(module(url)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("命令执行失败");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
        verify(executor).execute(command.capture(), any(Duration.class));
        assertThat(command.getValue()).contains(url).endsWith(Boolean.toString(allowInsecureHttp));
    }

    private GitSyncProperties properties() {
        GitSyncProperties properties = new GitSyncProperties();
        properties.setWorkspace(directory.resolve("workspace").toString());
        return properties;
    }

    private GitRepositorySyncService service(GitSyncProperties properties, CommandExecutor executor) {
        return new GitRepositorySyncService(properties, executor, new ObjectMapper(),
                new GitWorkspacePathResolver(properties), new BundledScriptInstaller(),
                directory.resolve("scripts").toString());
    }

    private static LogModuleConfig module(String url) {
        LogModuleConfig module = new LogModuleConfig();
        module.setCodeSyncEnabled(true);
        module.setEnvironment("test");
        module.setSystemCode("system");
        module.setModuleCode("module");
        module.setGitBranch("main");
        module.setGitRepositoryUrl(url);
        return module;
    }
}
