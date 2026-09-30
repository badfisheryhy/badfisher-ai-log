package io.github.badfisher.ailog.persistence.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.badfisher.ailog.domain.analysis.RootCauseRuleRepository;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.issue.RootCauseMatchTarget;
import io.github.badfisher.ailog.domain.issue.RootCauseRule;
import io.github.badfisher.ailog.domain.issue.RootCauseRuleType;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogClassifyRuleEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogClassifyRuleMapper;
import io.github.badfisher.ailog.persistence.config.entity.AiLogModuleConfigEntity;
import io.github.badfisher.ailog.persistence.config.mapper.AiLogModuleConfigMapper;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/**
 * 基于 MyBatis-Plus 的根因分类规则仓储。
 * <p>
 * 加载语义：
 * <ul>
 * <li>按 (environment, systemCode, moduleCode) 解析模块<b>当前</b>的 analysis_module
 *     （不校验 enabled——模块停用不影响已完成同步文件的解析）；</li>
 * <li>按 analysis_module 拉取启用规则，priority 升序、id 升序；</li>
 * <li>模块配置缺失、analysis_module 为空或规则集为空时返回空列表，分类退化为内置规则。</li>
 * </ul>
 * <p>
 * 【可调整点】规则数据非法（category/rule_type 不是合法枚举）时快速失败并携带规则 id，
 * 使对应文件分析任务进入 FAILED 状态暴露问题，由运维修正数据库后重试，而不是静默丢规则。
 */
public class MybatisPlusRootCauseRuleRepository implements RootCauseRuleRepository {

    /** 分类规则 Mapper。 */
    private final AiLogClassifyRuleMapper ruleMapper;

    /** 模块配置 Mapper。 */
    private final AiLogModuleConfigMapper configMapper;

    /**
     * 构造规则仓储。
     *
     * @param rules          分类规则 Mapper
     * @param moduleConfigs  模块配置 Mapper
     */
    public MybatisPlusRootCauseRuleRepository(AiLogClassifyRuleMapper rules,
            AiLogModuleConfigMapper moduleConfigs) {
        ruleMapper = rules;
        configMapper = moduleConfigs;
    }

    @Override
    public List<RootCauseRule> findEnabledRules(String environment, String systemCode, String moduleCode) {
        AiLogModuleConfigEntity config = configMapper.selectOne(
                Wrappers.<AiLogModuleConfigEntity>lambdaQuery()
                        .eq(AiLogModuleConfigEntity::getEnvironment, environment)
                        .eq(AiLogModuleConfigEntity::getSystemCode, systemCode)
                        .eq(AiLogModuleConfigEntity::getModuleCode, moduleCode));
        if (config == null || config.getAnalysisModule() == null
                || config.getAnalysisModule().trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<AiLogClassifyRuleEntity> entities = ruleMapper.selectList(
                Wrappers.<AiLogClassifyRuleEntity>lambdaQuery()
                        .eq(AiLogClassifyRuleEntity::getAnalysisModule, config.getAnalysisModule())
                        .eq(AiLogClassifyRuleEntity::getEnabled, Boolean.TRUE)
                        .orderByAsc(AiLogClassifyRuleEntity::getPriority)
                        .orderByAsc(AiLogClassifyRuleEntity::getId));
        List<RootCauseRule> result = new ArrayList<RootCauseRule>(entities.size());
        for (AiLogClassifyRuleEntity entity : entities) {
            result.add(toRule(entity));
        }
        return result;
    }

    /**
     * 实体转领域规则；非法枚举值快速失败并携带规则 id。
     *
     * @param entity 规则实体
     * @return 领域规则
     * @throws IllegalStateException category 或 rule_type 非法时抛出
     */
    private static RootCauseRule toRule(AiLogClassifyRuleEntity entity) {
        RootCauseCategory category;
        try {
            category = RootCauseCategory.valueOf(entity.getCategory());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid classify rule category for rule id="
                    + entity.getId() + ": " + entity.getCategory(), ex);
        }
        RootCauseRuleType ruleType;
        try {
            ruleType = RootCauseRuleType.valueOf(entity.getRuleType());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid classify rule type for rule id="
                    + entity.getId() + ": " + entity.getRuleType(), ex);
        }
        int priority = entity.getPriority() == null ? 100 : entity.getPriority().intValue();
        RootCauseMatchTarget matchTarget;
        try {
            matchTarget = RootCauseMatchTarget.valueOf(entity.getMatchTarget());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid classify rule match target for rule id="
                    + entity.getId() + ": " + entity.getMatchTarget(), ex);
        }
        boolean expected = Boolean.TRUE.equals(entity.getExpected());
        boolean aiRequired = Boolean.TRUE.equals(entity.getAiRequired());
        if (expected && category != RootCauseCategory.BUSINESS) {
            throw new IllegalStateException("Expected classify rule must use BUSINESS category, rule id="
                    + entity.getId());
        }
        if (expected && aiRequired) {
            throw new IllegalStateException("Expected classify rule must not require AI, rule id="
                    + entity.getId());
        }
        if (!hasText(entity.getReasonCode())) {
            throw new IllegalStateException("Classify rule must define reasonCode, rule id="
                    + entity.getId());
        }
        return new RootCauseRule(entity.getId(), entity.getAnalysisModule(), category, ruleType,
                matchTarget, entity.getPattern(), priority, expected, aiRequired,
                entity.getReasonCode());
    }
}
