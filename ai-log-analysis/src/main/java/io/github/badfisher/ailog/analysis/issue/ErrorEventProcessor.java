package io.github.badfisher.ailog.analysis.issue;

import io.github.badfisher.ailog.domain.issue.AnalyzedError;
import io.github.badfisher.ailog.domain.issue.ExceptionStructure;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.issue.RootCauseDecision;
import io.github.badfisher.ailog.domain.log.LogEvent;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/** 将 ERROR 事件转换为结构化分析结果的处理器。 */
public final class ErrorEventProcessor {

    /** 无法从异常结构或 Header 获取消息时，兜底正文最大保留长度。 */
    private static final int MAX_FALLBACK_MESSAGE_LENGTH = 4096;

    /** 异常结构提取器。 */
    private final ExceptionStructureExtractor exceptionStructureExtractor;
    /** 栈摘要简化器。 */
    private final StackSimplifier stackSimplifier;
    /** 根因分类器。 */
    private final RootCauseClassifier rootCauseClassifier;
    /** 触发渠道分类器。 */
    private final TriggerChannelClassifier triggerChannelClassifier;
    /** 指纹生成器。 */
    private final ErrorContentNormalizer contentNormalizer;
    /** 结构化消息模板器。 */
    private final MessageTemplater messageTemplater;

    /**
     * 构造处理器，使用默认触发渠道分类器。
     *
     * @param exceptionStructureExtractor 异常结构提取器
     * @param stackSimplifier             栈摘要简化器
     * @param rootCauseClassifier         根因分类器
     * @param contentNormalizer        指纹生成器
     */
    public ErrorEventProcessor(ExceptionStructureExtractor exceptionStructureExtractor,
            StackSimplifier stackSimplifier, RootCauseClassifier rootCauseClassifier,
            ErrorContentNormalizer contentNormalizer) {
        this(exceptionStructureExtractor, stackSimplifier, rootCauseClassifier,
                new TriggerChannelClassifier(), contentNormalizer, new MessageTemplater());
    }

    /**
     * 构造处理器。
     *
     * @param exceptionStructureExtractor 异常结构提取器
     * @param stackSimplifier             栈摘要简化器
     * @param rootCauseClassifier         根因分类器
     * @param triggerChannelClassifier    触发渠道分类器
     * @param contentNormalizer        指纹生成器
     */
    public ErrorEventProcessor(ExceptionStructureExtractor exceptionStructureExtractor,
            StackSimplifier stackSimplifier, RootCauseClassifier rootCauseClassifier,
            TriggerChannelClassifier triggerChannelClassifier,
            ErrorContentNormalizer contentNormalizer) {
        this(exceptionStructureExtractor, stackSimplifier, rootCauseClassifier,
                triggerChannelClassifier, contentNormalizer, new MessageTemplater());
    }

    /**
     * 构造处理器，并显式指定消息模板器。
     *
     * @param exceptionStructureExtractor 异常结构提取器
     * @param stackSimplifier             栈摘要简化器
     * @param rootCauseClassifier         根因分类器
     * @param triggerChannelClassifier    触发渠道分类器
     * @param contentNormalizer        指纹生成器
     * @param messageTemplater            结构化消息模板器
     */
    public ErrorEventProcessor(ExceptionStructureExtractor exceptionStructureExtractor,
            StackSimplifier stackSimplifier, RootCauseClassifier rootCauseClassifier,
            TriggerChannelClassifier triggerChannelClassifier,
            ErrorContentNormalizer contentNormalizer, MessageTemplater messageTemplater) {
        this.exceptionStructureExtractor = exceptionStructureExtractor;
        this.stackSimplifier = stackSimplifier;
        this.rootCauseClassifier = rootCauseClassifier;
        this.triggerChannelClassifier = triggerChannelClassifier;
        this.contentNormalizer = contentNormalizer;
        this.messageTemplater = messageTemplater;
    }

    /** 将完整 ERROR 事件转换为可供 Incident 聚合使用的结构化结果。 */
    public AnalyzedError process(LogEvent event) {
        if (event == null || !event.isError()) {
            throw new IllegalArgumentException("只能处理ERROR事件");
        }
        ExceptionStructure structure = exceptionStructureExtractor.extract(event);
        RootCauseDecision decision = rootCauseClassifier.decide(structure, event);
        RootCauseCategory category = decision.getCategory();
        String sourceMessage = fingerprintMessage(event, structure);
        String normalizedMessage = messageTemplater.template(
                contentNormalizer.normalize(sourceMessage));
        // Keep both legacy fingerprint fields, but calculate their shared sample key once.
        String sampleKey = contentNormalizer.sampleKey(structure, normalizedMessage);
        return new AnalyzedError(event, structure, category,
                triggerChannelClassifier.classify(event), stackSimplifier.simplify(structure),
                normalizedMessage, sampleKey, sampleKey,
                ErrorContentNormalizer.GROUPING_MARKER, decision.getMatchedRuleId(),
                decision.isExpected(), decision.isAiRequired(), decision.getReasonCode());
    }

    /** 按根因消息、顶层异常消息、Header 消息、正文的顺序选择指纹消息。 */
    private static String fingerprintMessage(LogEvent event, ExceptionStructure structure) {
        if (hasText(structure.getRootCauseMessage())) {
            return structure.getRootCauseMessage();
        }
        if (hasText(structure.getExceptionMessage())) {
            return structure.getExceptionMessage();
        }
        if (hasText(event.getMessage())) {
            return event.getMessage();
        }
        return fallbackBody(event.getContent());
    }

    /** 无合法 Header 时使用正文兜底；可识别的 Header 前缀不得进入指纹。 */
    private static String fallbackBody(String content) {
        if (!hasText(content)) {
            return "{empty-event}";
        }
        String body = content.trim();
        int separator = body.indexOf(" - ");
        int newline = body.indexOf('\n');
        if (separator >= 0 && (newline < 0 || separator < newline)) {
            body = body.substring(separator + 3).trim();
        }
        if (!hasText(body)) {
            return "{empty-event}";
        }
        return body.length() <= MAX_FALLBACK_MESSAGE_LENGTH
                ? body : body.substring(0, MAX_FALLBACK_MESSAGE_LENGTH);
    }
}
