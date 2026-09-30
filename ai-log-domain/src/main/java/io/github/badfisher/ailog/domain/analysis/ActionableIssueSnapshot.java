package io.github.badfisher.ailog.domain.analysis;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 分析的自包含问题快照。
 *
 * <p>身份和基础分类来自永久 Group；次数及时间来自创建 Item 时同批聚合的全部关联 Event，
 * 固化后不随后续事实变化。异常、业务位置及消息统一来自选定的 Representative Event。</p>
 */
@Data
public final class ActionableIssueSnapshot {

    /** Issue 聚合 ID。
     * -- GETTER --
     *  返回 Issue 聚合 ID。
     *
     */
    private final long issueGroupId;
    /** 环境编码。
     * -- GETTER --
     *  返回环境编码。
     *
     */
    private final String environment;
    /** 系统编码。
     * -- GETTER --
     *  返回系统编码。
     *
     */
    private final String systemCode;
    /** 模块编码。
     * -- GETTER --
     *  返回模块编码。
     *
     */
    private final String moduleCode;
    /** 稳定指纹 SHA-256。
     * -- GETTER --
     *  返回稳定指纹。
     *
     */
    private final String stableFingerprint;
    /** 指纹算法版本。
     * -- GETTER --
     *  返回指纹算法版本。
     *
     */
    private final String fingerprintVersion;
    /** 根因分类。
     * -- GETTER --
     *  返回根因分类。
     *
     */
    private final String rootCauseCategory;
    /** 触发通道。
     * -- GETTER --
     *  返回触发通道。
     *
     */
    private final String triggerChannel;
    /** 最外层异常类。
     * -- GETTER --
     *  返回最外层异常类。
     *
     */
    private final String exceptionClass;
    /** 根因异常类。
     * -- GETTER --
     *  返回根因异常类。
     *
     */
    private final String rootCauseException;
    /** 首个业务栈帧类名。
     * -- GETTER --
     *  返回首个业务栈帧类名。
     *
     */
    private final String businessClass;
    /** 首个业务栈帧方法名。
     * -- GETTER --
     *  返回首个业务栈帧方法名。
     *
     */
    private final String businessMethod;
    /** 代表样本归一化消息。
     * -- GETTER --
     *  返回 代表样本归一化消息。
     *
     */
    private final String normalizedMessage;
    /** 代表样本命中的数据库规则 ID；未命中数据库规则时为 {@code null}。
     * -- GETTER --
     *  返回命中的数据库规则 ID。
     *
     */
    private final Long matchedRuleId;
    /** 创建分析时关联 Event 的 occurrence 总次数。
     * -- GETTER --
     *  返回创建分析时关联 Event 的 occurrence 总次数。
     *
     */
    private final long windowEventCount;
    /** 同批 Event 聚合的首次发生时间。
     * -- GETTER --
     *  返回同批 Event 聚合的首次发生时间。
     *
     */
    private final LocalDateTime windowFirstSeen;
    /** 同批 Event 聚合的最近发生时间。
     * -- GETTER --
     *  返回同批 Event 聚合的最近发生时间。
     *
     */
    private final LocalDateTime windowLastSeen;
    /** 选定代表 Event ID，用于确定性排序与代表性证据。
     * -- GETTER --
     *  返回选定代表 Event ID。
     *
     */
    private final long latestEventIdInWindow;
    /** 选定代表 Event 的归一化消息。
     * -- GETTER --
     *  返回选定代表 Event 的归一化消息。
     *
     */
    private final String latestNormalizedMessage;
    /** 选定代表 Event 的简化调用栈。
     * -- GETTER --
     *  返回选定代表 Event 的简化调用栈。
     *
     */
    private final String latestSimplifiedStack;

    /**
     * 构造可操作 Issue 快照。
     *
     * @param issueGroupId       Issue 聚合 ID
     * @param environment        环境编码
     * @param systemCode         系统编码
     * @param module             模块编码
     * @param stableFingerprint  稳定指纹
     * @param fingerprintVersion 指纹算法版本
     * @param category           根因分类
     * @param channel            触发通道
     * @param exceptionClass     最外层异常类
     * @param rootCauseException 根因异常类
     * @param businessClass      首个业务栈帧类名
     * @param businessMethod     首个业务栈帧方法名
     * @param normalizedMessage  代表样本归一化消息
     * @param matchedRuleId      命中的数据库规则 ID
     * @param eventCount         创建分析时关联 Event 的 occurrence 总次数
     * @param firstSeen          同批 Event 聚合的首次发生时间
     * @param lastSeen           同批 Event 聚合的最近发生时间
     * @param latestEventId      选定代表 Event ID
     * @param latestMessage      窗口内最后入库事件归一化消息
     * @param latestStack        窗口内最后入库事件简化调用栈
     */
    public ActionableIssueSnapshot(long issueGroupId, String environment, String systemCode,
            String module, String stableFingerprint, String fingerprintVersion, String category,
            String channel, String exceptionClass, String rootCauseException,
            String businessClass, String businessMethod, String normalizedMessage,
            Long matchedRuleId, long eventCount, LocalDateTime firstSeen, LocalDateTime lastSeen,
            long latestEventId, String latestMessage, String latestStack) {
        this.issueGroupId = issueGroupId;
        this.environment = environment;
        this.systemCode = systemCode;
        this.moduleCode = module;
        this.stableFingerprint = stableFingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.rootCauseCategory = category;
        this.triggerChannel = channel;
        this.exceptionClass = exceptionClass;
        this.rootCauseException = rootCauseException;
        this.businessClass = businessClass;
        this.businessMethod = businessMethod;
        this.normalizedMessage = normalizedMessage;
        this.matchedRuleId = matchedRuleId;
        windowEventCount = eventCount;
        windowFirstSeen = firstSeen;
        windowLastSeen = lastSeen;
        latestEventIdInWindow = latestEventId;
        latestNormalizedMessage = latestMessage;
        latestSimplifiedStack = latestStack;
    }

}
