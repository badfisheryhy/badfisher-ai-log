package io.github.badfisher.ailog.domain.analysis;

import java.util.List;

import io.github.badfisher.ailog.domain.issue.RootCauseRule;

/**
 * 根因分类规则读取端口。
 * <p>
 * 实现负责按模块当前配置的 {@code analysis_module} 解析规则集合；
 * 模块配置缺失、未配置分析模块或规则集为空时返回空列表，此时分类退化为内置规则。
 */
public interface RootCauseRuleRepository {

    /**
     * 查询指定模块启用中的分类规则，按优先级升序返回。
     *
     * @param environment 环境标识
     * @param systemCode  系统编码
     * @param moduleCode  模块编码
     * @return 启用规则列表，无规则时为空列表
     */
    List<RootCauseRule> findEnabledRules(String environment, String systemCode, String moduleCode);
}
