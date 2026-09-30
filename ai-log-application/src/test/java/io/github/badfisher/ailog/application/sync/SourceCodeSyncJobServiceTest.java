package io.github.badfisher.ailog.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.application.sync.SourceCodeSyncJobService.JobResult;
import io.github.badfisher.ailog.application.tool.SourceCodeSyncTool;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.domain.config.LogModuleConfigRepository;

/** 独立源码同步任务的全量执行、失败隔离和空结果测试。 */
class SourceCodeSyncJobServiceTest {

    @Test
    void synchronizesEveryCodeEnabledModuleInRepositoryOrder() {
        RecordingSourceCodeSyncTool syncTool = new RecordingSourceCodeSyncTool(null);
        SourceCodeSyncJobService service = service(syncTool,
                module("demo", "sample-service"),
                module("ad", "advertising"),
                module("web", "platform"));

        JobResult result = service.run(" prod ");

        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(3);
        assertThat(result.getFailureCount()).isZero();
        assertThat(syncTool.calls).containsExactly(
                "demo/sample-service", "ad/advertising", "web/platform");
    }

    @Test
    void continuesWithRemainingModulesAfterSingleModuleFailure() {
        RecordingSourceCodeSyncTool syncTool =
                new RecordingSourceCodeSyncTool("ad/advertising");
        SourceCodeSyncJobService service = service(syncTool,
                module("demo", "sample-service"),
                module("ad", "advertising"),
                module("web", "platform"));

        JobResult result = service.run("prod");

        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(result.getFailedModules()).containsExactly("ad/advertising");
        assertThat(syncTool.calls).containsExactly(
                "demo/sample-service", "ad/advertising", "web/platform");
    }

    @Test
    void returnsEmptySuccessWhenNoModuleRequiresCodeSync() {
        RecordingSourceCodeSyncTool syncTool = new RecordingSourceCodeSyncTool(null);
        SourceCodeSyncJobService service = service(syncTool);

        JobResult result = service.run("prod");

        assertThat(result.getTotalCount()).isZero();
        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getFailureCount()).isZero();
        assertThat(syncTool.calls).isEmpty();
    }

    private static SourceCodeSyncJobService service(SourceCodeSyncTool syncTool,
            LogModuleConfig... modules) {
        return new SourceCodeSyncJobService(
                new FixedRepository(Arrays.asList(modules)), syncTool);
    }

    private static LogModuleConfig module(String systemCode, String moduleCode) {
        LogModuleConfig module = new LogModuleConfig();
        module.setEnvironment("prod");
        module.setSystemCode(systemCode);
        module.setModuleCode(moduleCode);
        module.setEnabled(true);
        module.setCodeSyncEnabled(true);
        return module;
    }

    /** 仅提供独立源码任务所需查询结果的测试仓储。 */
    private static final class FixedRepository implements LogModuleConfigRepository {
        private final List<LogModuleConfig> modules;

        private FixedRepository(List<LogModuleConfig> configuredModules) {
            modules = configuredModules;
        }

        @Override
        public List<LogModuleConfig> findCodeSyncEnabledModules(String environment) {
            assertThat(environment).isEqualTo("prod");
            return modules;
        }

        @Override
        public List<LogModuleConfig> findEnabledModules(String environment, String systemCode) {
            return Collections.emptyList();
        }

        @Override
        public Optional<LogModuleConfig> findEnabledModule(String environment,
                String systemCode, String moduleCode) {
            return Optional.empty();
        }
    }

    /** 记录调用顺序，并可对一个指定模块注入失败。 */
    private static final class RecordingSourceCodeSyncTool implements SourceCodeSyncTool {
        private final String failingModule;
        private final List<String> calls = new ArrayList<String>();

        private RecordingSourceCodeSyncTool(String failureModule) {
            failingModule = failureModule;
        }

        @Override
        public void syncLatest(LogModuleConfig module) {
            String identity = module.getSystemCode() + "/" + module.getModuleCode();
            calls.add(identity);
            if (identity.equals(failingModule)) {
                throw new IllegalStateException("simulated Git failure");
            }
        }
    }
}
