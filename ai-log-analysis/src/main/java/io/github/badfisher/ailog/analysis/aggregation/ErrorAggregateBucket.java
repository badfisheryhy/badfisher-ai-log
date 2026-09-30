package io.github.badfisher.ailog.analysis.aggregation;

import java.time.Instant;

import io.github.badfisher.ailog.domain.aggregation.AggregatedError;
import io.github.badfisher.ailog.domain.aggregation.ErrorSampleSnapshot;
import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository.ErrorEntry;

/** 单个聚合身份的计数、时间窗口和唯一轻量代表样本。 */
final class ErrorAggregateBucket {
    private final AggregationDecision decision;
    private final SamplePolicy samplePolicy;
    private final int maxSampleImproveAttempts;
    private long occurrenceCount;
    private Instant firstSeenTime;
    private Instant lastSeenTime;
    private ErrorSampleSnapshot sample;
    private long sampleResidentBytes;
    private int sampleImproveAttempts;
    private boolean sampleFrozen;

    ErrorAggregateBucket(AggregationDecision aggregationDecision, SamplePolicy policy,
            int maximumSampleImproveAttempts) {
        decision = aggregationDecision;
        samplePolicy = policy;
        maxSampleImproveAttempts = maximumSampleImproveAttempts;
    }

    /** 接收一次出现，返回代表样本替换造成的真实驻留字节变化。 */
    long accept(ErrorEntry entry) {
        Instant eventTime = entry.getError().getEvent().getTimestamp();
        occurrenceCount++;
        firstSeenTime = minimum(firstSeenTime, eventTime);
        lastSeenTime = maximum(lastSeenTime, eventTime);

        if (sampleFrozen) {
            return 0L;
        }
        ErrorSampleSnapshot candidate = samplePolicy.create(entry);
        sampleImproveAttempts++;
        if (!samplePolicy.shouldReplace(sample, candidate, decision.getCategory(),
                decision.isExpected())) {
            freezeIfNecessary();
            return 0L;
        }
        long previousBytes = sampleResidentBytes;
        sample = candidate;
        sampleResidentBytes = estimateSampleBytes(candidate);
        freezeIfNecessary();
        return sampleResidentBytes - previousBytes;
    }

    private void freezeIfNecessary() {
        sampleFrozen = samplePolicy.isComplete(sample, decision.getCategory(),
                decision.isExpected())
                || sampleImproveAttempts >= maxSampleImproveAttempts;
    }

    AggregatedError snapshot() {
        return new AggregatedError(decision.getAggregateType(), decision.getAggregateKey(),
                decision.getCategory(),
                decision.isExpected(), decision.isAiRequired(),
                decision.getReasonCode(),
                decision.getStableFingerprint(), decision.getFingerprintVersion(), occurrenceCount,
                firstSeenTime, lastSeenTime, sample.getMatchedRuleId(),
                samplePolicy.quality(sample), sample);
    }

    private long estimateSampleBytes(ErrorSampleSnapshot value) {
        long characters = length(value.getThreadName()) + length(value.getTid())
                + length(value.getTraceId()) + length(value.getRequestId())
                + length(value.getExceptionClass()) + length(value.getExceptionMessage())
                + length(value.getRootCauseException()) + length(value.getRootCauseMessage())
                + length(value.getBusinessClass()) + length(value.getBusinessMethod())
                + length(value.getNormalizedMessage()) + length(value.getSimplifiedStack())
                + length(value.getStrictFingerprint()) + length(value.getStableFingerprint())
                + length(value.getSampleContent()) + length(value.getMatchType());
        return 256L + characters * 2L;
    }

    private Instant minimum(Instant current, Instant candidate) {
        if (current == null || candidate == null) {
            return current == null ? candidate : current;
        }
        return candidate.isBefore(current) ? candidate : current;
    }

    private Instant maximum(Instant current, Instant candidate) {
        if (current == null || candidate == null) {
            return current == null ? candidate : current;
        }
        return candidate.isAfter(current) ? candidate : current;
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
