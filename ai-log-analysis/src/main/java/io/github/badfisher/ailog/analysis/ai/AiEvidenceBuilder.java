package io.github.badfisher.ailog.analysis.ai;

import java.util.ArrayList;
import java.util.List;

import io.github.badfisher.ailog.domain.ai.AiIssueEvidence;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence.EvidenceSample;
import io.github.badfisher.ailog.domain.analysis.ActionableIssueSnapshot;
import io.github.badfisher.ailog.domain.analysis.RepresentativeEvent;

/**
 * AI 证据构建器：把可操作 Issue 快照与有限代表性事件组装为有界 Evidence。
 *
 * <p>边界规则：样本数量、消息长度、栈长度全部有上限；样本保持仓储完整度优先顺序，
 * 超出上限时保留前若干条。栈与消息截断保留前缀
 * （Java 堆栈最有价值的异常头与最早业务帧在开头），截断不改变其余字段。</p>
 *
 * <p>构建结果只包含事实字段，不含构建时间等易变信息，为 evidenceHash 的稳定输入做准备；
 * TID 上下文增强在后续版本通过扩展字段接入，本版本不实现。</p>
 */
public final class AiEvidenceBuilder {

    /** 默认样本上限：最多三个代表样本。 */
    public static final int DEFAULT_MAX_SAMPLES = 3;

    /** 默认消息字符上限，与异常结构提取的消息保留上限保持同量级。 */
    public static final int DEFAULT_MAX_MESSAGE_CHARS = 4096;

    /** 默认栈字符上限：栈前缀（异常头与业务帧）具备最高诊断价值。 */
    public static final int DEFAULT_MAX_STACK_CHARS = 4096;


    /** 样本数量上限。 */
    private final int maxSamples;

    /** 消息字符上限。 */
    private final int maxMessageChars;

    /** 栈字符上限。 */
    private final int maxStackChars;

    /**
     * 使用默认上限构建证据构建器。
     */
    public AiEvidenceBuilder() {
        this(DEFAULT_MAX_SAMPLES, DEFAULT_MAX_MESSAGE_CHARS, DEFAULT_MAX_STACK_CHARS);
    }

    /**
     * 构建证据构建器。
     *
     * @param samples       样本数量上限，必须为正
     * @param messageChars  消息字符上限，必须为正
     * @param stackChars    栈字符上限，必须为正
     */
    public AiEvidenceBuilder(int samples, int messageChars, int stackChars) {
        if (samples < 1 || messageChars < 1 || stackChars < 1) {
            throw new IllegalArgumentException(
                    "Evidence limits must be positive: samples=" + samples
                            + ", messageChars=" + messageChars + ", stackChars=" + stackChars);
        }
        maxSamples = samples;
        maxMessageChars = messageChars;
        maxStackChars = stackChars;
    }

    /**
     * 构建单个 Issue 的 AI 分析证据。
     *
     * <p>代表性消息与栈取第一个样本（主代表证据）；样本为空时回退到
     * 快照携带的最近事件字段，保证 Evidence 始终可构建。</p>
     *
     * @param snapshot 可操作 Issue 快照，非空
     * @param samples  代表性事件列表，非空；允许为空列表但会触发快照回退
     * @return 有界 AI 分析证据
     */
    public AiIssueEvidence build(ActionableIssueSnapshot snapshot, List<RepresentativeEvent> samples) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Issue snapshot must not be null");
        }
        if (samples == null) {
            throw new IllegalArgumentException("Representative events must not be null");
        }
        // 保持仓储选定的主样本，不用最新时间覆盖完整度选择。
        List<RepresentativeEvent> bounded = boundSamples(samples);
        List<EvidenceSample> evidenceSamples = new ArrayList<EvidenceSample>(bounded.size());
        for (RepresentativeEvent event : bounded) {
            evidenceSamples.add(new EvidenceSample(event.getEventId(), event.getLogTime(),
                    event.getMatchType(), event.isTruncated(), event.getExceptionClass(),
                    truncate(event.getExceptionMessage(), maxMessageChars),
                    event.getRootCauseException(),
                    truncate(event.getRootCauseMessage(), maxMessageChars),
                    truncate(event.getNormalizedMessage(), maxMessageChars),
                    truncate(event.getSimplifiedStack(), maxStackChars),
                    truncate(event.getSampleContent(), maxMessageChars),
                    event.isSampleContentTruncated() || (event.getSampleContent() != null
                            && event.getSampleContent().length() > maxMessageChars)));
        }
        String representativeMessage;
        String representativeStackTrace;
        if (evidenceSamples.isEmpty()) {
            representativeMessage = truncate(snapshot.getLatestNormalizedMessage(), maxMessageChars);
            representativeStackTrace = truncate(snapshot.getLatestSimplifiedStack(), maxStackChars);
        } else {
            EvidenceSample latest = evidenceSamples.get(0);
            representativeMessage = latest.getNormalizedMessage();
            representativeStackTrace = latest.getSimplifiedStack();
        }
        return new AiIssueEvidence(snapshot.getIssueGroupId(), snapshot.getEnvironment(),
                snapshot.getSystemCode(), snapshot.getModuleCode(), snapshot.getStableFingerprint(),
                snapshot.getFingerprintVersion(), snapshot.getRootCauseCategory(),
                snapshot.getTriggerChannel(), snapshot.getExceptionClass(),
                snapshot.getRootCauseException(), snapshot.getBusinessClass(),
                snapshot.getBusinessMethod(), truncate(snapshot.getNormalizedMessage(),
                        maxMessageChars), snapshot.getMatchedRuleId(),
                snapshot.getWindowEventCount(),
                snapshot.getWindowFirstSeen(), snapshot.getWindowLastSeen(),
                representativeMessage, representativeStackTrace, evidenceSamples);
    }

    /**
     * 样本边界控制：只保留完整度排序最靠前的有限样本。
     */
    private List<RepresentativeEvent> boundSamples(List<RepresentativeEvent> samples) {
        return new ArrayList<RepresentativeEvent>(
                samples.subList(0, Math.min(samples.size(), maxSamples)));
    }

    /** 保留前缀的空安全截断；null 与未超长字符串原样返回。 */
    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }
}
