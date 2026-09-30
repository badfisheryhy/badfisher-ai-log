package io.github.badfisher.ailog.domain.ai;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;

/**
 * 单个 Issue 的 AI 分析证据。
 *
 * <p>Evidence 是 Issue 快照与有限代表性事件的有界组合，是外发 AI 的唯一输入载体：
 * 禁止把整份日志文件或全部 Event 直接交给 Provider。</p>
 *
 * <p>字段为不可变事实，不含构建时间等易变信息，序列化顺序由字段声明顺序决定，
 * 为后续 evidenceHash 的稳定输入做准备；occurrenceCount 是否参与哈希由哈希策略决定。
 * TID 上下文等增强信息预留为后续扩展字段，不得混入现有字段语义。</p>
 */
@Getter
public final class AiIssueEvidence {

    /** Issue 聚合 ID。 */
    private final long issueId;
    /** 环境编码。 */
    private final String environment;
    /** 系统编码。 */
    private final String systemCode;
    /** 模块编码。 */
    private final String moduleCode;
    /** 稳定指纹 SHA-256。 */
    private final String stableFingerprint;
    /** 指纹算法版本。 */
    private final String fingerprintVersion;
    /** 根因分类。 */
    private final String rootCauseCategory;
    /** 触发通道。 */
    private final String triggerChannel;
    /** 最外层异常类。 */
    private final String exceptionClass;
    /** 根因异常类。 */
    private final String rootCauseException;
    /** 首个业务栈帧类名。 */
    private final String businessClass;
    /** 首个业务栈帧方法名。 */
    private final String businessMethod;
    /** Issue 归一化消息模板。 */
    private final String messageTemplate;
    /** 最近分类决策命中的数据库规则 ID；未命中数据库规则时为 {@code null}。 */
    private final Long matchedRuleId;
    /** 窗口内非预期事件数。 */
    private final long occurrenceCount;
    /** 窗口内首次出现时间。 */
    private final LocalDateTime firstOccurredAt;
    /** 窗口内最近出现时间。 */
    private final LocalDateTime lastOccurredAt;
    /** 代表性异常消息（窗口内最近事件）。 */
    private final String representativeMessage;
    /** 代表性调用栈（窗口内最近事件）。 */
    private final String representativeStackTrace;
    /** 有界代表性事件样本，按时间升序且保证首尾齐全。 */
    private final List<EvidenceSample> samples;

    /**
     * 构造 AI 分析证据。
     *
     * @param issueId                Issue 聚合 ID
     * @param environment            环境编码
     * @param systemCode             系统编码
     * @param moduleCode             模块编码
     * @param stableFingerprint      稳定指纹
     * @param fingerprintVersion     指纹算法版本
     * @param rootCauseCategory      根因分类
     * @param triggerChannel         触发通道
     * @param exceptionClass         最外层异常类
     * @param rootCauseException     根因异常类
     * @param businessClass          首个业务栈帧类名
     * @param businessMethod         首个业务栈帧方法名
     * @param messageTemplate        归一化消息模板
     * @param matchedRuleId          命中的数据库规则 ID
     * @param occurrenceCount        窗口内非预期事件数
     * @param firstOccurredAt        窗口内首次出现时间
     * @param lastOccurredAt         窗口内最近出现时间
     * @param representativeMessage  代表性异常消息
     * @param representativeStackTrace 代表性调用栈
     * @param evidenceSamples        有界代表性事件样本
     */
    public AiIssueEvidence(long issueId, String environment, String systemCode, String moduleCode,
            String stableFingerprint, String fingerprintVersion, String rootCauseCategory,
            String triggerChannel, String exceptionClass, String rootCauseException,
            String businessClass, String businessMethod, String messageTemplate,
            Long matchedRuleId, long occurrenceCount, LocalDateTime firstOccurredAt,
            LocalDateTime lastOccurredAt,
            String representativeMessage, String representativeStackTrace,
            List<EvidenceSample> evidenceSamples) {
        this.issueId = issueId;
        this.environment = environment;
        this.systemCode = systemCode;
        this.moduleCode = moduleCode;
        this.stableFingerprint = stableFingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.rootCauseCategory = rootCauseCategory;
        this.triggerChannel = triggerChannel;
        this.exceptionClass = exceptionClass;
        this.rootCauseException = rootCauseException;
        this.businessClass = businessClass;
        this.businessMethod = businessMethod;
        this.messageTemplate = messageTemplate;
        this.matchedRuleId = matchedRuleId;
        this.occurrenceCount = occurrenceCount;
        this.firstOccurredAt = firstOccurredAt;
        this.lastOccurredAt = lastOccurredAt;
        this.representativeMessage = representativeMessage;
        this.representativeStackTrace = representativeStackTrace;
        samples = Collections.unmodifiableList(new ArrayList<EvidenceSample>(evidenceSamples));
    }

    /** Selected context only; this class never contains the full INFO stream. */
    private List<InfoContext> infoContext = Collections.emptyList();
    private String infoContextStatus = "DISABLED";

    public AiIssueEvidence withInfoContext(List<InfoContext> context, String status) {
        AiIssueEvidence copy = new AiIssueEvidence(issueId, environment, systemCode, moduleCode,
                stableFingerprint, fingerprintVersion, rootCauseCategory, triggerChannel,
                exceptionClass, rootCauseException, businessClass, businessMethod, messageTemplate,
                matchedRuleId, occurrenceCount, firstOccurredAt, lastOccurredAt,
                representativeMessage, representativeStackTrace, samples);
        copy.infoContext = List.copyOf(context);
        copy.infoContextStatus = status;
        return copy;
    }

    /** 单条代表性事件样本的受限事实。 */
    @Getter
    public static final class EvidenceSample {

        /** 事件 ID。 */
        private final long eventId;
        /** 日志时间。 */
        private final LocalDateTime logTime;
        /** 准入类型：STRICT_ERROR、FALLBACK_ERROR。 */
        private final String matchType;
        /** 事件在源文件层面是否被截断。 */
        private final boolean truncated;
        /** 最外层异常类。 */
        private final String exceptionClass;
        /** 最外层异常消息（受限长度）。 */
        private final String exceptionMessage;
        /** 根因异常类。 */
        private final String rootCauseException;
        /** 根因异常消息（受限长度）。 */
        private final String rootCauseMessage;
        /** 脱敏归一化消息（受限长度）。 */
        private final String normalizedMessage;
        /** 简化调用栈（受限长度）。 */
        private final String simplifiedStack;
        /** 聚合 Event 保存的唯一代表原文。 */
        private final String sampleContent;
        /** Sample 是否因长度上限再次截断。 */
        private final boolean sampleContentTruncated;

        /**
         * 构造样本。
         *
         * @param eventId            事件 ID
         * @param logTime            日志时间
         * @param matchType          准入类型
         * @param truncated          源文件层面是否截断
         * @param exceptionClass     最外层异常类
         * @param exceptionMessage   最外层异常消息
         * @param rootCauseException 根因异常类
         * @param rootCauseMessage   根因异常消息
         * @param normalizedMessage  归一化消息
         * @param simplifiedStack    简化调用栈
         */
        public EvidenceSample(long eventId, LocalDateTime logTime, String matchType,
                boolean truncated, String exceptionClass, String exceptionMessage,
                String rootCauseException, String rootCauseMessage, String normalizedMessage,
                String simplifiedStack) {
            this(eventId, logTime, matchType, truncated, exceptionClass, exceptionMessage,
                    rootCauseException, rootCauseMessage, normalizedMessage, simplifiedStack,
                    null, false);
        }

        /** 构造带聚合代表原文的单样本证据。 */
        public EvidenceSample(long eventId, LocalDateTime logTime, String matchType,
                boolean truncated, String exceptionClass, String exceptionMessage,
                String rootCauseException, String rootCauseMessage, String normalizedMessage,
                String simplifiedStack, String sampleContent, boolean sampleContentTruncated) {
            this.eventId = eventId;
            this.logTime = logTime;
            this.matchType = matchType;
            this.truncated = truncated;
            this.exceptionClass = exceptionClass;
            this.exceptionMessage = exceptionMessage;
            this.rootCauseException = rootCauseException;
            this.rootCauseMessage = rootCauseMessage;
            this.normalizedMessage = normalizedMessage;
            this.simplifiedStack = simplifiedStack;
            this.sampleContent = sampleContent;
            this.sampleContentTruncated = sampleContentTruncated;
        }

    }
}
