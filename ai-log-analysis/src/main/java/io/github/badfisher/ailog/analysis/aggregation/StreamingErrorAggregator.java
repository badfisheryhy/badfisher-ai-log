package io.github.badfisher.ailog.analysis.aggregation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.badfisher.ailog.domain.aggregation.AggregateBatch;
import io.github.badfisher.ailog.domain.aggregation.AggregatedError;
import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository.ErrorEntry;
import io.github.badfisher.ailog.domain.issue.AnalyzedError;

/**
 * 文件级有界流式错误聚合器。
 * <p>
 * Map 只驻留聚合身份、统计窗口和一条限长样本；达到任一阈值即输出统计增量批次。
 */
public final class StreamingErrorAggregator {
    private static final long BUCKET_FIXED_BYTES = 384L;

    private final String environment;
    private final String systemCode;
    private final String moduleCode;
    private final SamplePolicy samplePolicy;
    private final int maxBuckets;
    private final long maxEstimatedBytes;
    private final long flushAfterAcceptedErrors;
    private final int maxSampleImproveAttempts;
    private final Map<String, ErrorAggregateBucket> buckets =
            new LinkedHashMap<String, ErrorAggregateBucket>();
    private long estimatedResidentBytes;
    private long acceptedErrorsSinceFlush;

    /** 构造单个文件使用的聚合器，调用方在 EOF 必须执行 {@link #drain()}。 */
    public StreamingErrorAggregator(String env, String system, String module,
            int maximumBuckets, long maximumEstimatedBytes,
            long acceptedErrorFlushThreshold, int maximumSampleContentLength,
            int maximumSampleImprovementAttempts) {
        requireText(env, "environment");
        requireText(system, "systemCode");
        requireText(module, "moduleCode");
        if (maximumBuckets <= 0 || maximumEstimatedBytes <= 0
                || acceptedErrorFlushThreshold <= 0 || maximumSampleImprovementAttempts <= 0) {
            throw new IllegalArgumentException("aggregation limits must be positive");
        }
        environment = env;
        systemCode = system;
        moduleCode = module;
        samplePolicy = new SamplePolicy(maximumSampleContentLength);
        maxBuckets = maximumBuckets;
        maxEstimatedBytes = maximumEstimatedBytes;
        flushAfterAcceptedErrors = acceptedErrorFlushThreshold;
        maxSampleImproveAttempts = maximumSampleImprovementAttempts;
    }

    /** 接收一条错误；达到任一阈值时返回增量批次，否则返回 {@code null}。 */
    public AggregateBatch add(ErrorEntry entry) {
        if (entry == null || entry.getError() == null) {
            throw new IllegalArgumentException("errorEntry and analyzedError must not be null");
        }
        AnalyzedError error = entry.getError();
        AggregationDecision decision = new AggregationDecision(environment, systemCode,
                moduleCode, error);
        String bucketKey = decision.getAggregateType().name() + ":" + decision.getAggregateKey();
        ErrorAggregateBucket bucket = buckets.get(bucketKey);
        if (bucket == null) {
            bucket = new ErrorAggregateBucket(decision, samplePolicy, maxSampleImproveAttempts);
            buckets.put(bucketKey, bucket);
            estimatedResidentBytes += estimateDecisionBytes(decision);
        }
        estimatedResidentBytes += bucket.accept(entry);
        acceptedErrorsSinceFlush++;
        if (buckets.size() >= maxBuckets
                || estimatedResidentBytes >= maxEstimatedBytes
                || acceptedErrorsSinceFlush >= flushAfterAcceptedErrors) {
            return drain();
        }
        return null;
    }

    /** 输出剩余聚合增量，并完整清空文件内当前批次状态。 */
    public AggregateBatch drain() {
        List<AggregatedError> errors = new ArrayList<AggregatedError>(buckets.size());
        for (ErrorAggregateBucket bucket : buckets.values()) {
            errors.add(bucket.snapshot());
        }
        buckets.clear();
        estimatedResidentBytes = 0L;
        acceptedErrorsSinceFlush = 0L;
        return new AggregateBatch(errors);
    }

    public boolean isEmpty() {
        return buckets.isEmpty();
    }

    int bucketCount() {
        return buckets.size();
    }

    long estimatedResidentBytes() {
        return estimatedResidentBytes;
    }

    long acceptedErrorsSinceFlush() {
        return acceptedErrorsSinceFlush;
    }

    private long estimateDecisionBytes(AggregationDecision decision) {
        long characters = length(decision.getAggregateKey())
                + length(decision.getReasonCode()) + length(decision.getStableFingerprint())
                + length(decision.getFingerprintVersion());
        return BUCKET_FIXED_BYTES + characters * 2L;
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
