package io.github.badfisher.ailog.persistence.analysis;

import java.util.Arrays;
import java.util.List;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.badfisher.ailog.domain.analysis.SuppressRule;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogSuppressRuleEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogSuppressRuleMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MybatisPlusSuppressRuleRepositoryTest {

    private AiLogSuppressRuleMapper ruleMapper;
    private MybatisPlusSuppressRuleRepository repository;

    @BeforeEach
    void setUp() {
        initializeTableMetadata(AiLogSuppressRuleEntity.class);
        ruleMapper = mock(AiLogSuppressRuleMapper.class);
        repository = new MybatisPlusSuppressRuleRepository(ruleMapper);
    }

    @Test
    void loadsRulesBySystemAndModuleScope() {
        when(ruleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(rule(2L, "demo", "order"),
                        rule(3L, "demo", "order")));

        List<SuppressRule> rules = repository.findEnabledRules("demo", "order");

        assertThat(rules).extracting(SuppressRule::getId).containsExactly(2L, 3L);
        ArgumentCaptor<Wrapper> queries = ArgumentCaptor.forClass(Wrapper.class);
        verify(ruleMapper).selectList(queries.capture());
        assertThat(queries.getValue().getSqlSegment())
                .contains("system_code", "module_code", "enabled", "id", "ORDER BY")
                .doesNotContain("environment");
    }

    private static AiLogSuppressRuleEntity rule(long id, String system, String module) {
        AiLogSuppressRuleEntity entity = new AiLogSuppressRuleEntity();
        entity.setId(Long.valueOf(id));
        entity.setSystemCode(system);
        entity.setModuleCode(module);
        entity.setKeyword("known error");
        entity.setEnabled(Boolean.TRUE);
        return entity;
    }

    private static void initializeTableMetadata(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                    new MybatisConfiguration(), entityType.getName());
            TableInfoHelper.initTableInfo(assistant, entityType);
        }
    }
}
