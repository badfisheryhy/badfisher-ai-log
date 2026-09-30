package io.github.badfisher.ailog.persistence.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.issue.RootCauseRule;
import io.github.badfisher.ailog.domain.issue.RootCauseRuleType;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogClassifyRuleEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogClassifyRuleMapper;
import io.github.badfisher.ailog.persistence.config.entity.AiLogModuleConfigEntity;
import io.github.badfisher.ailog.persistence.config.mapper.AiLogModuleConfigMapper;

/** 根因分类规则仓储加载语义测试。 */
class MybatisPlusRootCauseRuleRepositoryTest {

    private AiLogModuleConfigMapper configMapper;
    private AiLogClassifyRuleMapper ruleMapper;
    private MybatisPlusRootCauseRuleRepository repository;

    @BeforeEach
    void setUp() {
        initializeTableMetadata(AiLogModuleConfigEntity.class);
        initializeTableMetadata(AiLogClassifyRuleEntity.class);
        configMapper = mock(AiLogModuleConfigMapper.class);
        ruleMapper = mock(AiLogClassifyRuleMapper.class);
        repository = new MybatisPlusRootCauseRuleRepository(ruleMapper, configMapper);
    }

    @Test
    void loadsEnabledRulesForModulesAnalysisModuleRegardlessOfEnabledFlag() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(moduleConfig("custom-module"));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(
                rule(1L, "EXTERNAL_SERVICE", "KEYWORD", "api get error", 10),
                rule(2L, "BUSINESS", "KEYWORD", "同步通道失败", 20)));

        List<RootCauseRule> rules = repository.findEnabledRules("prod", "demo", "sample-service");

        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).getCategory()).isEqualTo(RootCauseCategory.EXTERNAL_SERVICE);
        assertThat(rules.get(0).getId()).isEqualTo(Long.valueOf(1L));
        assertThat(rules.get(0).getPattern()).isEqualTo("api get error");
        assertThat(rules.get(1).getCategory()).isEqualTo(RootCauseCategory.BUSINESS);
        assertThat(rules.get(1).isExpected()).isFalse();
    }

    @Test
    void returnsEmptyWhenModuleConfigMissing() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThat(repository.findEnabledRules("prod", "demo", "sample-service")).isEmpty();
    }

    @Test
    void returnsEmptyWhenModuleConfigHasBlankAnalysisModule() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(moduleConfig("  "));

        assertThat(repository.findEnabledRules("prod", "demo", "sample-service")).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoRuleConfigured() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(moduleConfig("custom-module"));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());

        assertThat(repository.findEnabledRules("prod", "demo", "sample-service")).isEmpty();
    }

    @Test
    void invalidCategoryFailsFastWithRuleId() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(moduleConfig("custom-module"));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(
                rule(7L, "NOT_A_CATEGORY", "KEYWORD", "api get error", 10)));

        assertThatThrownBy(() -> repository.findEnabledRules("prod", "demo", "sample-service"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rule id=7");
    }

    @Test
    void loadsSuppressFlagForBusinessRule() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(moduleConfig("custom-module"));
        AiLogClassifyRuleEntity entity =
                rule(8L, "BUSINESS", "KEYWORD", "已存在退款", 10);
        entity.setExpected(Boolean.TRUE);
        entity.setAiRequired(Boolean.FALSE);
        entity.setReasonCode("DUPLICATE_REQUEST");
        when(ruleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(entity));

        List<RootCauseRule> rules = repository.findEnabledRules("prod", "demo", "sample-service");

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).isExpected()).isTrue();
        assertThat(rules.get(0).getReasonCode()).isEqualTo("DUPLICATE_REQUEST");
        assertThat(rules.get(0).getReasonCode()).isEqualTo("DUPLICATE_REQUEST");
    }

    @Test
    void rejectsSuppressFlagForTechnicalCategory() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(moduleConfig("custom-module"));
        AiLogClassifyRuleEntity entity =
                rule(9L, "DATABASE", "KEYWORD", "deadlock", 10);
        entity.setExpected(Boolean.TRUE);
        entity.setAiRequired(Boolean.FALSE);
        when(ruleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(entity));

        assertThatThrownBy(() -> repository.findEnabledRules("prod", "demo", "sample-service"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rule id=9");
    }

    @Test
    void rejectsSuppressBusinessRuleWithoutAggregateCode() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(moduleConfig("custom-module"));
        AiLogClassifyRuleEntity entity =
                rule(10L, "BUSINESS", "KEYWORD", "订单未完全", 10);
        entity.setExpected(Boolean.TRUE);
        entity.setAiRequired(Boolean.FALSE);
        entity.setReasonCode(null);
        when(ruleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(entity));

        assertThatThrownBy(() -> repository.findEnabledRules("prod", "demo", "sample-service"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reasonCode")
                .hasMessageContaining("rule id=10");
    }

    private static AiLogModuleConfigEntity moduleConfig(String analysisModule) {
        AiLogModuleConfigEntity config = new AiLogModuleConfigEntity();
        config.setId(Long.valueOf(1L));
        config.setEnvironment("prod");
        config.setSystemCode("demo");
        config.setModuleCode("sample-service");
        config.setAnalysisModule(analysisModule);
        return config;
    }

    private static AiLogClassifyRuleEntity rule(long id, String category, String ruleType,
            String pattern, int priority) {
        AiLogClassifyRuleEntity entity = new AiLogClassifyRuleEntity();
        entity.setId(Long.valueOf(id));
        entity.setAnalysisModule("custom-module");
        entity.setCategory(category);
        entity.setRuleType(ruleType);
        entity.setMatchTarget("ALL");
        entity.setPattern(pattern);
        entity.setPriority(Integer.valueOf(priority));
        entity.setEnabled(Boolean.TRUE);
        entity.setExpected(Boolean.FALSE);
        entity.setAiRequired(Boolean.TRUE);
        entity.setReasonCode(category + "_TEST");
        return entity;
    }

    /** 为纯 Mock 测试初始化 MyBatis-Plus Lambda 字段缓存。 */
    private static void initializeTableMetadata(Class<?> entityType) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, entityType);
    }
}
