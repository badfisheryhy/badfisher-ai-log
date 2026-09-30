package io.github.badfisher.ailog.analysis.issue;

import java.util.Collections;
import java.util.List;

import io.github.badfisher.ailog.domain.issue.RootCauseRule;

/**
 * 按任务快照组装 {@link ErrorEventProcessor} 的工厂。
 * <p>
 * 提取器、栈简化器、触发渠道分类器与指纹生成器均为无状态线程安全组件，随工厂共享；
 * {@link RootCauseClassifier} 携带每个文件分析任务的规则快照，随任务单独构造，
 * 保证"任务内规则不变、任务间规则可调"的快照语义。
 * <p>
 * 【可调整点】后续若引入按模块定制的提取器参数（如业务包前缀），在此扩展工厂参数即可。
 */
public final class ErrorEventProcessorFactory {

    /** 异常结构提取器（无状态共享）。 */
    private final ExceptionStructureExtractor exceptionStructureExtractor;

    /** 栈摘要简化器（无状态共享）。 */
    private final StackSimplifier stackSimplifier;

    /** 触发渠道分类器（无状态共享）。 */
    private final TriggerChannelClassifier triggerChannelClassifier;

    /** 指纹生成器（无状态共享）。 */
    private final ErrorContentNormalizer contentNormalizer;

    /**
     * 构造处理器工厂。
     *
     * @param exceptionStructureExtractor 异常结构提取器
     * @param stackSimplifier             栈摘要简化器
     * @param triggerChannelClassifier    触发渠道分类器
     * @param contentNormalizer        指纹生成器
     */
    public ErrorEventProcessorFactory(ExceptionStructureExtractor exceptionStructureExtractor,
            StackSimplifier stackSimplifier, TriggerChannelClassifier triggerChannelClassifier,
            ErrorContentNormalizer contentNormalizer) {
        this.exceptionStructureExtractor = exceptionStructureExtractor;
        this.stackSimplifier = stackSimplifier;
        this.triggerChannelClassifier = triggerChannelClassifier;
        this.contentNormalizer = contentNormalizer;
    }

    /**
     * 使用内置分类规则创建处理器。
     *
     * @return ERROR 事件处理器
     */
    public ErrorEventProcessor create() {
        return create(Collections.<RootCauseRule>emptyList());
    }

    /**
     * 使用指定规则快照创建处理器。
     *
     * @param rules 本次任务的根因分类规则快照
     * @return ERROR 事件处理器
     */
    public ErrorEventProcessor create(List<RootCauseRule> rules) {
        return new ErrorEventProcessor(exceptionStructureExtractor, stackSimplifier,
                new RootCauseClassifier(rules), triggerChannelClassifier, contentNormalizer);
    }
}
