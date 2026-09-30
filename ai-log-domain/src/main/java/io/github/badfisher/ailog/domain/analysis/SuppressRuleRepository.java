package io.github.badfisher.ailog.domain.analysis;

import java.util.List;

/** 按文件加载已启用的前置过滤规则快照。 */
public interface SuppressRuleRepository {

    List<SuppressRule> findEnabledRules(String systemCode, String moduleCode);
}
