package io.github.badfisher.ailog.domain.aggregation;

import java.time.Instant;

import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import lombok.Getter;

/** 单次 drain 输出的一条分析任务级聚合错误增量、代表样本及其质量评分。 */
@Getter
public final class AggregatedError {
    private final AggregateType aggregateType;
    private final String aggregateKey;
    private final RootCauseCategory category;
    private final boolean expected;
    private final boolean aiRequired;
    private final String reasonCode;
    private final String stableFingerprint;
    private final String fingerprintVersion;
    private final long occurrenceCount;
    private final Instant firstSeenTime;
    private final Instant lastSeenTime;
    private final Long matchedRuleId;
    private final int sampleQualityScore;
    private final ErrorSampleSnapshot sample;

    /** 构造聚合统计、代表样本与样本质量评分不可分割的输出记录。 */
    public AggregatedError(AggregateType type, String key,
            RootCauseCategory rootCategory, boolean expectedValue,
            boolean requiresAi,
            String reason, String stable, String fingerprint,
            long count, Instant firstSeen, Instant lastSeen, Long ruleId,
            int qualityScore, ErrorSampleSnapshot representativeSample) {
        aggregateType = type;
        aggregateKey = key;
        category = rootCategory;
        expected = expectedValue;
        aiRequired = requiresAi;
        reasonCode = reason;
        stableFingerprint = stable;
        fingerprintVersion = fingerprint;
        occurrenceCount = count;
        firstSeenTime = firstSeen;
        lastSeenTime = lastSeen;
        matchedRuleId = ruleId;
        sampleQualityScore = qualityScore;
        sample = representativeSample;
    }
}
