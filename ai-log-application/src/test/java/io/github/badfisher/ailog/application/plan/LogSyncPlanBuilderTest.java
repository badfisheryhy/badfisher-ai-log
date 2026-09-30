package io.github.badfisher.ailog.application.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.application.path.LocalLogPathResolver;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.domain.sync.LogSyncPlan;

class LogSyncPlanBuilderTest {
    @TempDir Path root;

    @Test
    void buildsErrorAndApplicationCandidatesAndIsolatedPaths() {
        LogSyncProperties properties = new LogSyncProperties();
        properties.setRootDirectory(root.toString());
        LogSyncPlanBuilder builder = new LogSyncPlanBuilder(
                new LocalLogPathResolver(properties), new LogModuleConfigValidator());

        LogSyncPlan plan = builder.build(config("sample-service", "badfisher-sample-service"), LocalDate.of(2026, 8, 16));

        assertThat(plan.getItems()).hasSize(2);
        assertThat(plan.getItems().get(0).getCandidateFiles()).containsExactly(
                "20260816_badfisher-sample-service-err.log", "20260816_badfisher-sample-service-err.log.gz");
        assertThat(plan.getItems().get(1).getCandidateFiles()).containsExactly(
                "20260816_badfisher-sample-service-all.log", "20260816_badfisher-sample-service-all.log.gz");
        assertThat(plan.getItems().get(0).getReadyDirectory().toString())
                .contains("prod", "demo", "sample-service", "20260816", "ready", "error");
    }

    private static LogModuleConfig config(String module, String prefix) {
        LogModuleConfig config = new LogModuleConfig();
        config.setEnabled(true);
        config.setEnvironment("prod");
        config.setSystemCode("demo");
        config.setModuleCode(module);
        config.setServerHost("log.example");
        config.setServerPort(22);
        config.setSshUsername("op_read");
        config.setRemoteDirectory("/data/log/" + module);
        config.setLogFilePrefix(prefix);
        config.setSyncErrorLog(true);
        config.setSyncAllLog(true);
        return config;
    }
}
