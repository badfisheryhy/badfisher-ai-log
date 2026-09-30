package io.github.badfisher.ailog.domain.issue;

import lombok.Getter;

/** 一次可审计的根因分类决策。 */
@Getter
public final class RootCauseDecision {

    /** 根因分类。 */
    private final RootCauseCategory category;

    /** 命中的数据库规则ID；内置规则和异常先验命中时为空。 */
    private final Long matchedRuleId;

    /** 是否为规则明确标记的预期业务异常。 */
    private final boolean expected;

    /** 规则是否要求进入 AI 分析。 */
    private final boolean aiRequired;

    /** BUSINESS 聚合原因编码，允许为空。 */
    private final String reasonCode;

    public RootCauseDecision(RootCauseCategory value, Long ruleId) {
        this(value, ruleId, false);
    }

    public RootCauseDecision(RootCauseCategory value, Long ruleId, boolean expectedValue) {
        this(value, ruleId, expectedValue, null);
    }

    /** 构造完整分类决策。 */
    public RootCauseDecision(RootCauseCategory value, Long ruleId, boolean expectedValue,
            String reason) {
        this(value, ruleId, expectedValue, !expectedValue, reason);
    }

    public RootCauseDecision(RootCauseCategory value, Long ruleId, boolean expectedValue,
            boolean requiresAi, String reason) {
        category = value;
        matchedRuleId = ruleId;
        expected = expectedValue;
        aiRequired = requiresAi;
        reasonCode = reason;
    }
}
