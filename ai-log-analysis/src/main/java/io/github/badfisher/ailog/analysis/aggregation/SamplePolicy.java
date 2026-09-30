package io.github.badfisher.ailog.analysis.aggregation;

import io.github.badfisher.ailog.domain.aggregation.ErrorSampleSnapshot;
import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository.ErrorEntry;
import io.github.badfisher.ailog.domain.issue.AnalyzedError;
import io.github.badfisher.ailog.domain.issue.ExceptionStructure;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.log.LogEvent;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/** 创建限长轻量样本，并以有限质量提升规则选择唯一代表样本。 */
public final class SamplePolicy {
    private final int maxContentLength;

    public SamplePolicy(int maximumContentLength) {
        if (maximumContentLength <= 0) {
            throw new IllegalArgumentException("maxSampleContentLength must be positive");
        }
        maxContentLength = maximumContentLength;
    }

    /** 立即复制所需字段，禁止聚合桶长期引用完整 {@link AnalyzedError}。 */
    public ErrorSampleSnapshot create(ErrorEntry entry) {
        AnalyzedError error = entry.getError();
        LogEvent event = error.getEvent();
        ExceptionStructure structure = error.getStructure();
        String content = limit(event.getContent());
        boolean contentTruncated = length(event.getContent()) > maxContentLength;
        return new ErrorSampleSnapshot(event.getTimestamp(), event.getLocationMode(),
                event.getStartLine(),
                event.getEndLine(), event.getStartByte(), event.getEndByte(),
                event.getThreadName(), event.getTid(), event.getTraceId(), event.getRequestId(),
                event.getLoggerClass(), event.getLoggerMethod(), event.getLoggerLine(),
                error.getTriggerChannel(),
                structure.getExceptionClass(), structure.getExceptionMessage(),
                structure.getRootCauseException(), structure.getRootCauseMessage(),
                structure.getBusinessClass(), structure.getBusinessMethod(),
                structure.getBusinessLine(), error.getNormalizedMessage(),
                error.getSimplifiedStack(), error.getStrictFingerprint(),
                error.getStableFingerprint(), content, event.isTruncated(), contentTruncated,
                entry.getMatchType(), error.getMatchedRuleId());
    }

    /** 仅当现有样本证据不完整且候选样本质量严格更高时允许替换。 */
    public boolean shouldReplace(ErrorSampleSnapshot current, ErrorSampleSnapshot candidate,
            RootCauseCategory category, boolean expected) {
        if (current == null) {
            return true;
        }
        if (isComplete(current, category, expected)) {
            return false;
        }
        return quality(candidate) > quality(current);
    }

    boolean isComplete(ErrorSampleSnapshot sample, RootCauseCategory category,
            boolean expected) {
        boolean hasCoreContent = hasText(sample.getSampleContent())
                && hasText(sample.getNormalizedMessage());
        if (category == RootCauseCategory.BUSINESS && expected) {
            return hasCoreContent;
        }
        boolean hasExceptionEvidence = hasText(sample.getRootCauseException())
                || hasText(sample.getSimplifiedStack());
        return hasCoreContent && hasExceptionEvidence;
    }

    /**
     * 代表样本质量评分（0-7），供跨 flush 样本替换和 Group 证据查询复用。
     *
     * <p>同一 aggregate 跨 flush 时，分数严格更高才整套替换样本快照，
     * 相同分数保留现有样本；Group 选样直接按入库评分排序，不重新评分。评分维度：
     * 归一化消息、根因异常、简化栈、业务栈帧、样本内容、未截断、追踪标识。</p>
     */
    public int quality(ErrorSampleSnapshot sample) {
        int score = hasText(sample.getNormalizedMessage()) ? 1 : 0;
        score += hasText(sample.getRootCauseException()) ? 1 : 0;
        score += hasText(sample.getSimplifiedStack()) ? 1 : 0;
        score += hasText(sample.getBusinessClass()) ? 1 : 0;
        score += hasText(sample.getSampleContent()) ? 1 : 0;
        score += (!sample.isSourceEventTruncated() && !sample.isSampleContentTruncated()) ? 1 : 0;
        score += (hasText(sample.getTid()) || hasText(sample.getTraceId())) ? 1 : 0;
        return score;
    }

    private String limit(String value) {
        if (value == null || value.length() <= maxContentLength) {
            return value;
        }
        return value.substring(0, maxContentLength);
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
