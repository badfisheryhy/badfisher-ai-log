package io.github.badfisher.ailog.domain.issue;

import lombok.Getter;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/** 数据库根因分类规则的不可变值对象。 */
@Getter
public final class RootCauseRule {

    private final Long id;
    private final String analysisModule;
    private final RootCauseCategory category;
    private final RootCauseRuleType ruleType;
    private final RootCauseMatchTarget matchTarget;
    private final String pattern;
    private final int priority;
    private final boolean expected;
    private final boolean aiRequired;
    private final String reasonCode;

    /** 便于测试构造的默认规则：匹配全部事实，非预期且需要 AI。 */
    public RootCauseRule(String module, RootCauseCategory value, RootCauseRuleType type,
            String ruleText, int order) {
        this(null, module, value, type, RootCauseMatchTarget.ALL, ruleText, order,
                false, true, value.name());
    }

    /** 兼容已有业务规则构造器，优先使用 reason 作为原因编码。 */
    public RootCauseRule(String module, RootCauseCategory value, RootCauseRuleType type,
            String ruleText, int order, boolean expectedValue, String aggregate, String reason) {
        this(null, module, value, type, RootCauseMatchTarget.ALL, ruleText, order,
                expectedValue, !expectedValue, firstText(reason, aggregate, value.name()));
    }

    /** 兼容携带数据库 ID 的测试规则。 */
    public RootCauseRule(Long ruleId, String module, RootCauseCategory value,
            RootCauseRuleType type, String ruleText, int order) {
        this(ruleId, module, value, type, RootCauseMatchTarget.ALL, ruleText, order,
                false, true, value.name());
    }

    /** 兼容已有持久化规则构造器。 */
    public RootCauseRule(Long ruleId, String module, RootCauseCategory value,
            RootCauseRuleType type, String ruleText, int order, boolean expectedValue,
            String aggregate, String reason) {
        this(ruleId, module, value, type, RootCauseMatchTarget.ALL, ruleText, order,
                expectedValue, !expectedValue, firstText(reason, aggregate, value.name()));
    }

    /** 构造数据库完整规则。 */
    public RootCauseRule(Long ruleId, String module, RootCauseCategory value,
            RootCauseRuleType type, RootCauseMatchTarget target, String ruleText, int order,
            boolean expectedValue, boolean requiresAi, String reason) {
        if (!hasText(module)) {
            throw new IllegalArgumentException("analysisModule is required");
        }
        if (value == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("ruleType is required");
        }
        if (target == null) {
            throw new IllegalArgumentException("matchTarget is required");
        }
        if (!hasText(ruleText)) {
            throw new IllegalArgumentException("pattern is required");
        }
        if (expectedValue && value != RootCauseCategory.BUSINESS) {
            throw new IllegalArgumentException("expected rule must use BUSINESS category");
        }
        if (expectedValue && requiresAi) {
            throw new IllegalArgumentException("expected rule must not require AI");
        }
        if (!hasText(reason)) {
            throw new IllegalArgumentException("reasonCode is required");
        }
        id = ruleId;
        analysisModule = module.trim();
        category = value;
        ruleType = type;
        matchTarget = target;
        pattern = ruleText.trim();
        priority = order;
        expected = expectedValue;
        aiRequired = requiresAi;
        reasonCode = reason.trim();
    }

    private static String firstText(String first, String second, String fallback) {
        if (hasText(first)) {
            return first.trim();
        }
        if (hasText(second)) {
            return second.trim();
        }
        return fallback;
    }
}
