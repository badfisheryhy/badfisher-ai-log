package io.github.badfisher.ailog.persistence.analysis;

import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.badfisher.ailog.domain.analysis.SuppressRule;
import io.github.badfisher.ailog.domain.analysis.SuppressRuleRepository;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogSuppressRuleEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogSuppressRuleMapper;

/** 根据系统和模块作用域加载已启用规则。 */
public class MybatisPlusSuppressRuleRepository implements SuppressRuleRepository {

    private final AiLogSuppressRuleMapper ruleMapper;

    public MybatisPlusSuppressRuleRepository(AiLogSuppressRuleMapper rules) {
        ruleMapper = rules;
    }

    @Override
    public List<SuppressRule> findEnabledRules(String systemCode, String moduleCode) {
        List<AiLogSuppressRuleEntity> entities = ruleMapper.selectList(
                Wrappers.<AiLogSuppressRuleEntity>lambdaQuery()
                        .eq(AiLogSuppressRuleEntity::getSystemCode, systemCode)
                        .eq(AiLogSuppressRuleEntity::getModuleCode, moduleCode)
                        .eq(AiLogSuppressRuleEntity::getEnabled, Boolean.TRUE)
                        .orderByAsc(AiLogSuppressRuleEntity::getId));
        List<SuppressRule> result = new ArrayList<SuppressRule>(entities.size());
        for (AiLogSuppressRuleEntity entity : entities) {
            result.add(new SuppressRule(entity.getId(), entity.getSystemCode(),
                    entity.getModuleCode(), entity.getKeyword()));
        }
        return result;
    }
}
