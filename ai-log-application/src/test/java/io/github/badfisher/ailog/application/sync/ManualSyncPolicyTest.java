package io.github.badfisher.ailog.application.sync;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import io.github.badfisher.ailog.application.command.LogSyncCommand;
import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.application.lock.DistributedLockManager;
import io.github.badfisher.ailog.application.tool.SourceCodeSyncTool;
import io.github.badfisher.ailog.domain.config.LogModuleConfigRepository;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository;
import io.github.badfisher.ailog.ingestion.sync.LogSyncException;
import io.github.badfisher.ailog.ingestion.sync.SyncErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ManualSyncPolicyTest {

    @Test
    void customEnvironmentRestrictionIsCaseInsensitiveAndPrecedesRepositoryAccess() {
        LogSyncProperties properties = properties();
        properties.setManualSyncDeniedEnvironments(List.of(" protected "));
        LogModuleConfigRepository modules = mock(LogModuleConfigRepository.class);
        LogSyncTaskRepository tasks = mock(LogSyncTaskRepository.class);

        assertThatThrownBy(() -> service(properties, modules, tasks).syncModuleManually(command("PROTECTED")))
                .isInstanceOfSatisfying(LogSyncException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(SyncErrorCode.MANUAL_SYNC_DISABLED));
        verifyNoInteractions(modules, tasks);
    }

    @Test
    void emptyPolicyDoesNotHardcodeProductionRestriction() {
        LogModuleConfigRepository modules = mock(LogModuleConfigRepository.class);
        LogSyncTaskRepository tasks = mock(LogSyncTaskRepository.class);

        assertThatThrownBy(() -> service(properties(), modules, tasks).syncModuleManually(command("prod")))
                .isInstanceOfSatisfying(LogSyncException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(SyncErrorCode.CONFIG_NOT_FOUND));
        verify(modules).findEnabledModule("prod", "system", "module");
        verifyNoInteractions(tasks);
    }

    private static LogSyncProperties properties() {
        LogSyncProperties properties = new LogSyncProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static LogSyncCommand command(String environment) {
        return new LogSyncCommand(environment, "system", "module", LocalDate.of(2026, 9, 1), false);
    }

    private static LogSyncApplicationService service(LogSyncProperties properties,
            LogModuleConfigRepository modules, LogSyncTaskRepository tasks) {
        return new LogSyncApplicationService(modules, tasks, null, null,
                mock(DistributedLockManager.class), Runnable::run, ZoneId.of("Asia/Shanghai"),
                mock(SourceCodeSyncTool.class), properties);
    }
}
