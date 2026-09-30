package io.github.badfisher.ailog.domain.issue;

import io.github.badfisher.ailog.domain.log.LogEvent;
import lombok.Getter;

/** 可直接进入 Incident/Issue 聚合的单个结构化 ERROR。 */
@Getter
public final class AnalyzedError {
    /** 原始日志事件。 */
    private final LogEvent event;

    /** 异常链结构化表示。 */
    private final ExceptionStructure structure;

    /** 根因分类。 */
    private final RootCauseCategory category;

    /** 命中的数据库规则ID。 */
    private final Long matchedRuleId;

    /** 是否为规则明确标记的预期业务异常。 */
    private final boolean expected;

    /** 是否需要进入 AI 分析。 */
    private final boolean aiRequired;

    /** BUSINESS 聚合原因编码，允许为空。 */
    private final String reasonCode;

    /** 触发通道。 */
    private final TriggerChannel triggerChannel;

    /** 简化后的调用栈文本。 */
    private final String simplifiedStack;

    /** 归一化后的异常消息。 */
    private final String normalizedMessage;

    /** 严格指纹：全消息参与计算，变更即失效。 */
    private final String strictFingerprint;

    /** 稳定指纹：仅关键特征参与计算，跨版本稳定。 */
    private final String stableFingerprint;

    /** 指纹算法版本号。 */
    private final String fingerprintVersion;

    /**
     * 构造分析结果。
     *
     * @param event             原始日志事件
     * @param structure         异常链结构化表示
     * @param category          根因分类
     * @param triggerChannel    触发通道
     * @param simplifiedStack   简化后的调用栈文本
     * @param normalizedMessage 归一化后的异常消息
     * @param strictFingerprint 严格指纹
     * @param stableFingerprint 稳定指纹
     * @param fingerprintVersion 指纹算法版本号
     */
    public AnalyzedError(LogEvent event, ExceptionStructure structure, RootCauseCategory category,
            TriggerChannel triggerChannel, String simplifiedStack, String normalizedMessage,
            String strictFingerprint, String stableFingerprint, String fingerprintVersion) {
        this(event, structure, category, triggerChannel, simplifiedStack, normalizedMessage,
                strictFingerprint, stableFingerprint, fingerprintVersion, null, false);
    }

    public AnalyzedError(LogEvent event, ExceptionStructure structure, RootCauseCategory category,
            TriggerChannel triggerChannel, String simplifiedStack, String normalizedMessage,
            String strictFingerprint, String stableFingerprint, String fingerprintVersion,
            Long matchedRuleId) {
        this(event, structure, category, triggerChannel, simplifiedStack, normalizedMessage,
                strictFingerprint, stableFingerprint, fingerprintVersion, matchedRuleId, false);
    }

    public AnalyzedError(LogEvent event, ExceptionStructure structure, RootCauseCategory category,
            TriggerChannel triggerChannel, String simplifiedStack, String normalizedMessage,
            String strictFingerprint, String stableFingerprint, String fingerprintVersion,
            Long matchedRuleId, boolean expectedValue) {
        this(event, structure, category, triggerChannel, simplifiedStack, normalizedMessage,
                strictFingerprint, stableFingerprint, fingerprintVersion, matchedRuleId,
                expectedValue, null);
    }

    /**
     * 构造包含分类审计和 BUSINESS 聚合身份的完整分析结果。
     *
     * @param reasonCode BUSINESS 聚合原因编码，允许为空
     */
    public AnalyzedError(LogEvent event, ExceptionStructure structure, RootCauseCategory category,
            TriggerChannel triggerChannel, String simplifiedStack, String normalizedMessage,
            String strictFingerprint, String stableFingerprint, String fingerprintVersion,
            Long matchedRuleId, boolean expectedValue, String reasonCode) {
        this(event, structure, category, triggerChannel, simplifiedStack, normalizedMessage,
                strictFingerprint, stableFingerprint, fingerprintVersion, matchedRuleId,
                expectedValue, !expectedValue, reasonCode);
    }

    /** 构造包含规则 AI 路由事实的完整分析结果。 */
    public AnalyzedError(LogEvent event, ExceptionStructure structure, RootCauseCategory category,
            TriggerChannel triggerChannel, String simplifiedStack, String normalizedMessage,
            String strictFingerprint, String stableFingerprint, String fingerprintVersion,
            Long matchedRuleId, boolean expectedValue, boolean requiresAi,
            String reasonCode) {
        this.event = event;
        this.structure = structure;
        this.category = category;
        this.triggerChannel = triggerChannel;
        this.simplifiedStack = simplifiedStack;
        this.normalizedMessage = normalizedMessage;
        this.strictFingerprint = strictFingerprint;
        this.stableFingerprint = stableFingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.matchedRuleId = matchedRuleId;
        this.expected = expectedValue;
        this.aiRequired = requiresAi;
        this.reasonCode = reasonCode;
    }

}
