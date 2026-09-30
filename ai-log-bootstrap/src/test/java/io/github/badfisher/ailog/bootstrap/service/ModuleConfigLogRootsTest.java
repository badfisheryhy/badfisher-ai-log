package io.github.badfisher.ailog.bootstrap.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.bootstrap.controller.request.ModuleConfigRequest;
import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import io.github.badfisher.ailog.persistence.config.mapper.AiLogModuleConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ModuleConfigLogRootsTest {

    @TempDir
    Path directory;

    @Test
    void rejectsOutsideDirectoryBeforeAnyDatabaseWrite() throws Exception {
        LogSyncProperties properties = new LogSyncProperties();
        properties.setEnabled(false);
        properties.setAllowedLogRoots(List.of(Files.createDirectory(directory.resolve("allowed")).toString()));
        AiLogModuleConfigMapper mapper = mock(AiLogModuleConfigMapper.class);
        ManagementActorProvider actors = mock(ManagementActorProvider.class);
        ModuleConfigRequest request = new ModuleConfigRequest();
        request.setEnvironment("test");
        request.setSystemCode("system");
        request.setModuleCode("module");
        request.setRemoteDirectory(directory.toString());

        assertThatThrownBy(() -> new ModuleConfigManagementService(mapper, actors, properties).create(request))
                .isInstanceOf(BusinessException.class).hasMessageContaining("allowed-log-roots");
        verifyNoInteractions(mapper, actors);
    }
}
