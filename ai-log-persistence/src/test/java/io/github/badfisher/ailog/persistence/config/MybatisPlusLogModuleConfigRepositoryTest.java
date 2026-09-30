package io.github.badfisher.ailog.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.persistence.config.entity.AiLogModuleConfigEntity;
import io.github.badfisher.ailog.persistence.config.mapper.AiLogModuleConfigMapper;

class MybatisPlusLogModuleConfigRepositoryTest {
    @Test
    void codeSyncQueryRestrictsEnvironmentAndBothEnabledFlags() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiLogModuleConfigEntity.class);
        AiLogModuleConfigMapper mapper = mock(AiLogModuleConfigMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(java.util.Collections.emptyList());
        MybatisPlusLogModuleConfigRepository repository =
                new MybatisPlusLogModuleConfigRepository(mapper);

        repository.findCodeSyncEnabledModules("prod");

        ArgumentCaptor<Wrapper<AiLogModuleConfigEntity>> query =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(query.capture());
        String sql = query.getValue().getSqlSegment().toLowerCase(Locale.ROOT);
        assertThat(sql)
                .contains("environment", "code_sync_enabled")
                .matches("(?s).*\\benabled\\s*=.*")
                .contains("order by", "system_code", "sync_priority", "id");
        AbstractWrapper<?, ?, ?> abstractQuery =
                (AbstractWrapper<?, ?, ?>) query.getValue();
        assertThat(abstractQuery.getParamNameValuePairs().values())
                .contains("prod", Boolean.TRUE);
    }

    @Test
    void mapsMapperResultsToDomainConfiguration() {
        AiLogModuleConfigMapper mapper = mock(AiLogModuleConfigMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(
                entity(1L, "sample-service", 10),
                entity(2L, "asinking", 20)));
        MybatisPlusLogModuleConfigRepository repository = new MybatisPlusLogModuleConfigRepository(mapper);
        List<LogModuleConfig> modules = repository.findEnabledModules("prod", "demo");
        assertThat(modules).extracting(LogModuleConfig::getModuleCode).containsExactly("sample-service", "asinking");
        assertThat(modules.get(0).getServerPort()).isEqualTo(22);
        assertThat(modules.get(0).isCodeSyncEnabled()).isTrue();
        assertThat(modules.get(0).getGitRepositoryUrl())
                .isEqualTo("https://git.example/sample-service.git");
        assertThat(modules.get(0).getGitBranch()).isEqualTo("main");
    }

    @Test
    void nullSystemCodeDoesNotRestrictEnabledModuleQuery() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiLogModuleConfigEntity.class);
        AiLogModuleConfigMapper mapper = mock(AiLogModuleConfigMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(java.util.Collections.emptyList());
        MybatisPlusLogModuleConfigRepository repository =
                new MybatisPlusLogModuleConfigRepository(mapper);

        repository.findEnabledModules("prod", null);

        ArgumentCaptor<Wrapper<AiLogModuleConfigEntity>> query =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(query.capture());
        String sql = query.getValue().getSqlSegment().toLowerCase(Locale.ROOT);
        assertThat(sql).contains("environment", "enabled").doesNotContain("system_code");
        AbstractWrapper<?, ?, ?> abstractQuery = (AbstractWrapper<?, ?, ?>) query.getValue();
        assertThat(abstractQuery.getParamNameValuePairs().values())
                .contains("prod", Boolean.TRUE);
    }

    @Test
    void returnsEmptyWhenMapperFindsNoEnabledModule() {
        AiLogModuleConfigMapper mapper = mock(AiLogModuleConfigMapper.class);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThat(new MybatisPlusLogModuleConfigRepository(mapper)
                .findEnabledModule("prod", "demo", "disabled-module")).isEmpty();
    }

    private static AiLogModuleConfigEntity entity(Long id, String moduleCode, int priority) {
        AiLogModuleConfigEntity entity = new AiLogModuleConfigEntity();
        entity.setId(id);
        entity.setEnvironment("prod");
        entity.setSystemCode("demo");
        entity.setModuleCode(moduleCode);
        entity.setEnabled(Boolean.TRUE);
        entity.setServerHost("log.example");
        entity.setServerPort(Integer.valueOf(22));
        entity.setSshUsername("op_read");
        entity.setRemoteDirectory("/data/log/" + moduleCode);
        entity.setLogFilePrefix("badfisher-" + moduleCode);
        entity.setSyncErrorLog(Boolean.TRUE);
        entity.setSyncAllLog(Boolean.TRUE);
        entity.setLocalSubDirectory(moduleCode);
        entity.setParserProfile("JAVA");
        entity.setAnalysisModule("default");
        entity.setCodeSyncEnabled(Boolean.TRUE);
        entity.setGitRepositoryUrl("https://git.example/" + moduleCode + ".git");
        entity.setGitBranch("main");
        entity.setSyncPriority(Integer.valueOf(priority));
        return entity;
    }
}
