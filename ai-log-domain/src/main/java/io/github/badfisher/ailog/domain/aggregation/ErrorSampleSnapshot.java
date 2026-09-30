package io.github.badfisher.ailog.domain.aggregation;

import java.time.Instant;

import io.github.badfisher.ailog.domain.issue.TriggerChannel;
import io.github.badfisher.ailog.domain.log.LogLocationMode;
import lombok.Getter;

/** 聚合桶内唯一、限长且不再引用原始事件对象的代表错误样本。 */
@Getter
public final class ErrorSampleSnapshot {
    private final Instant logTime;
    private final LogLocationMode locationMode;
    private final long startLine;
    private final long endLine;
    private final long startByte;
    private final long endByte;
    private final String threadName;
    private final String tid;
    private final String traceId;
    private final String requestId;
    private final String loggerClass;
    private final String loggerMethod;
    private final Integer loggerLine;
    private final TriggerChannel triggerChannel;
    private final String exceptionClass;
    private final String exceptionMessage;
    private final String rootCauseException;
    private final String rootCauseMessage;
    private final String businessClass;
    private final String businessMethod;
    private final Integer businessLine;
    private final String normalizedMessage;
    private final String simplifiedStack;
    private final String strictFingerprint;
    private final String stableFingerprint;
    private final String sampleContent;
    private final boolean sourceEventTruncated;
    private final boolean sampleContentTruncated;
    private final String matchType;
    /** 代表样本命中的分类规则 ID；内置分类时为 {@code null}。 */
    private final Long matchedRuleId;

    /** 构造一条与 {@code AnalyzedError} 生命周期解耦的完整快照。 */
    public ErrorSampleSnapshot(Instant eventTime, LogLocationMode location,
            long firstLine, long lastLine,
            long firstByte, long lastByte, String thread, String transactionId,
            String trace, String request, String logger, String loggerOperation,
            Integer loggerLocationLine, TriggerChannel channel, String exception,
            String exceptionText,
            String rootException, String rootMessage, String businessLocation,
            String businessOperation, Integer businessLocationLine, String normalized,
            String stack, String strict, String stable, String content,
            boolean eventTruncated, boolean contentTruncated, String admissionType,
            Long ruleId) {
        logTime = eventTime;
        locationMode = location;
        startLine = firstLine;
        endLine = lastLine;
        startByte = firstByte;
        endByte = lastByte;
        threadName = thread;
        tid = transactionId;
        traceId = trace;
        requestId = request;
        loggerClass = logger;
        loggerMethod = loggerOperation;
        loggerLine = loggerLocationLine;
        triggerChannel = channel;
        exceptionClass = exception;
        exceptionMessage = exceptionText;
        rootCauseException = rootException;
        rootCauseMessage = rootMessage;
        businessClass = businessLocation;
        businessMethod = businessOperation;
        businessLine = businessLocationLine;
        normalizedMessage = normalized;
        simplifiedStack = stack;
        strictFingerprint = strict;
        stableFingerprint = stable;
        sampleContent = content;
        sourceEventTruncated = eventTruncated;
        sampleContentTruncated = contentTruncated;
        matchType = admissionType;
        matchedRuleId = ruleId;
    }
}
