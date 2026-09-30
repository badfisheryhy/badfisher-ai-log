package io.github.badfisher.ailog.domain.analysis;

import lombok.Getter;

/** 一条已启用的 ERROR 前置过滤规则。 */
@Getter
public final class SuppressRule {

    private final Long id;
    private final String systemCode;
    private final String moduleCode;
    private final String keyword;

    public SuppressRule(Long ruleId, String system, String module, String value) {
        id = ruleId;
        systemCode = system;
        moduleCode = module;
        keyword = value;
    }
}
