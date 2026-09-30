package io.github.badfisher.ailog.analysis.aggregation;

import io.github.badfisher.ailog.domain.text.Sha256;
import io.github.badfisher.ailog.domain.aggregation.AggregateType;
import io.github.badfisher.ailog.domain.issue.AnalyzedError;
import io.github.badfisher.ailog.domain.issue.ExceptionStructure;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import lombok.Getter;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/** 单条错误的规则分类结果和文件级 Group 身份。 */
@Getter
public final class AggregationDecision {

    /** 旧表非空字段的固定兼容值，不再作为版本分流条件。 */
    public static final String LEGACY_IDENTITY_MARKER = "rule-group";

    private final AggregateType aggregateType;
    private final String aggregateKey;
    private final RootCauseCategory category;
    private final boolean expected;
    private final boolean aiRequired;
    private final String reasonCode;
    private final String stableFingerprint;
    private final String fingerprintVersion;
    private final Long matchedRuleId;

    /** 根据 Rule 输出生成 Group 身份。 */
    public AggregationDecision(String environment, String systemCode, String moduleCode,
            AnalyzedError error) {
        if (error == null || error.getCategory() == null) {
            throw new IllegalArgumentException("analyzedError and category must not be null");
        }
        category = error.getCategory();
        expected = error.isExpected();
        aiRequired = error.isAiRequired();
        String configuredReasonCode = error.getReasonCode();
        if (expected && category == RootCauseCategory.BUSINESS && !hasText(configuredReasonCode)) {
            throw new IllegalArgumentException("expected BUSINESS rule must define reasonCode");
        }
        reasonCode = hasText(configuredReasonCode) ? configuredReasonCode : category.name();
        matchedRuleId = error.getMatchedRuleId();
        aggregateType = expected && category == RootCauseCategory.BUSINESS
                ? AggregateType.BUSINESS : AggregateType.ISSUE;
        stableFingerprint = groupKey(error, reasonCode);
        fingerprintVersion = LEGACY_IDENTITY_MARKER;
        aggregateKey = Sha256.sha256(new KeySource()
                .add(environment)
                .add(systemCode)
                .add(moduleCode)
                .add(aggregateType.name())
                .add(stableFingerprint)
                .value());
    }

    private static String groupKey(AnalyzedError error, String effectiveReasonCode) {
        ExceptionStructure structure = requireStructure(error);
        KeySource identity = new KeySource().add(error.getCategory().name());
        if (error.getCategory() == RootCauseCategory.UNKNOWN) {
            requireAnyIdentity("UNKNOWN", structure.getLoggerClass(),
                    structure.getLoggerMethod(), structure.getExceptionClass());
            return Sha256.sha256(identity
                    .add(structure.getLoggerClass())
                    .add(structure.getLoggerMethod())
                    .add(structure.getExceptionClass())
                    .value());
        }

        identity.add(effectiveReasonCode);
        if (error.isExpected() && error.getCategory() == RootCauseCategory.BUSINESS) {
            return Sha256.sha256(identity.value());
        }

        if (error.getCategory() == RootCauseCategory.BUSINESS) {
            requireAnyIdentity("BUSINESS", ownerClass(structure), ownerMethod(structure),
                    structure.getExceptionClass(), structure.getRootCauseException());
            return Sha256.sha256(identity
                    .add(ownerClass(structure))
                    .add(ownerMethod(structure))
                    .value());
        }

        requireAnyIdentity(error.getCategory().name(), ownerClass(structure),
                ownerMethod(structure), structure.getExceptionClass(),
                structure.getRootCauseException());
        return Sha256.sha256(identity
                .add(ownerClass(structure))
                .add(ownerMethod(structure))
                .add(structure.getExceptionClass())
                .add(structure.getRootCauseException())
                .value());
    }

    private static ExceptionStructure requireStructure(AnalyzedError error) {
        if (error.getStructure() == null) {
            throw new IllegalArgumentException("exceptionStructure must not be null");
        }
        return error.getStructure();
    }

    private static String ownerClass(ExceptionStructure structure) {
        return firstText(structure.getBusinessClass(), structure.getLoggerClass());
    }

    private static String ownerMethod(ExceptionStructure structure) {
        return firstText(structure.getBusinessMethod(), structure.getLoggerMethod());
    }

    private static String firstText(String preferred, String fallback) {
        return hasText(preferred) ? preferred : fallback;
    }

    private static void requireAnyIdentity(String category, String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return;
            }
        }
        throw new IllegalArgumentException(category + " group identity must not be empty");
    }

    /** 使用长度前缀编码字段，避免空值和分隔符碰撞。 */
    private static final class KeySource {
        private final StringBuilder source = new StringBuilder(256);

        private KeySource add(String value) {
            String actualValue = value == null ? "" : value;
            source.append(actualValue.length()).append(':').append(actualValue).append(';');
            return this;
        }

        private String value() {
            return source.toString();
        }
    }
}
