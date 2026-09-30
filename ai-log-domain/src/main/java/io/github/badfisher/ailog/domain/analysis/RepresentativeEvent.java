package io.github.badfisher.ailog.domain.analysis;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Issue 的代表性事件样本。
 *
 * <p>样本来自窗口内 {@code expected=0} 的事件事实，供 AI Evidence 构建有限证据；
 * 数量与栈长在仓储与 Evidence 层均有上限，禁止以完整事件列表替代。</p>
 */
@Getter
public final class RepresentativeEvent {

    /** 事件 ID。
     * -- GETTER --
     *  返回事件 ID。
     *
     * @return 事件 ID
     */
    private final long eventId;
    /** 来源文件记录 ID。
     * -- GETTER --
     *  返回来源文件记录 ID。
     *
     * @return 文件记录 ID
     */
    private final long fileRecordId;
    /** 日志 Header 时间。
     * -- GETTER --
     *  返回日志 Header 时间。
     *
     * @return 日志时间
     */
    private final LocalDateTime logTime;
    /** 准入类型：STRICT_ERROR、FALLBACK_ERROR。
     * -- GETTER --
     *  返回准入类型。
     *
     * @return 准入类型编码
     */
    private final String matchType;
    /** 日志定位模式。
     * -- GETTER --
     *  返回日志定位模式。
     *
     * @return 定位模式编码
     */
    private final String locationMode;
    /** 源文件起始行。
     * -- GETTER --
     *  返回源文件起始行。
     *
     * @return 起始行号
     */
    private final long startLine;
    /** 是否截断。
     * -- GETTER --
     *  返回是否截断。
     *
     * @return 截断返回 true
     */
    private final boolean truncated;
    /** 线程名。
     * -- GETTER --
     *  返回线程名。
     *
     * @return 线程名
     */
    private final String threadName;
    /** TraceId。
     * -- GETTER --
     *  返回 TraceId。
     *
     * @return TraceId
     */
    private final String traceId;
    /** TID。
     * -- GETTER --
     *  返回 TID。
     *
     * @return TID
     */
    private final String tid;
    /** RequestId。
     * -- GETTER --
     *  返回 RequestId。
     *
     * @return RequestId
     */
    private final String requestId;
    /** 最外层异常类。
     * -- GETTER --
     *  返回最外层异常类。
     *
     * @return 最外层异常类
     */
    private final String exceptionClass;
    /** 最外层异常消息。
     * -- GETTER --
     *  返回最外层异常消息。
     *
     * @return 最外层异常消息
     */
    private final String exceptionMessage;
    /** 根因异常类。
     * -- GETTER --
     *  返回根因异常类。
     *
     * @return 根因异常类
     */
    private final String rootCauseException;
    /** 根因异常消息。
     * -- GETTER --
     *  返回根因异常消息。
     *
     * @return 根因异常消息
     */
    private final String rootCauseMessage;
    /** 首个业务栈帧类名。
     * -- GETTER --
     *  返回首个业务栈帧类名。
     *
     * @return 首个业务栈帧类名
     */
    private final String businessClass;
    /** 首个业务栈帧方法名。
     * -- GETTER --
     *  返回首个业务栈帧方法名。
     *
     * @return 首个业务栈帧方法名
     */
    private final String businessMethod;
    /** 首个业务栈帧行号。
     * -- GETTER --
     *  返回首个业务栈帧行号。
     *
     * @return 首个业务栈帧行号；缺失时为 {@code null}
     */
    private final Integer businessLine;
    /** 脱敏归一化消息。
     * -- GETTER --
     *  返回归一化消息。
     *
     * @return 归一化消息
     */
    private final String normalizedMessage;
    /** 限制长度后的简化调用栈。
     * -- GETTER --
     *  返回简化调用栈。
     *
     * @return 简化调用栈
     */
    private final String simplifiedStack;
    /** 复用Event已保存的限长原始样本。 */
    private final String sampleContent;
    private final boolean sampleContentTruncated;

    /**
     * 构造代表性事件样本。
     *
     * @param eventId            事件 ID
     * @param fileRecordId       来源文件记录 ID
     * @param logTime            日志 Header 时间
     * @param matchType          准入类型
     * @param locationMode       日志定位模式
     * @param startLine          源文件起始行
     * @param truncated          是否截断
     * @param threadName         线程名
     * @param traceId            TraceId
     * @param tid                TID
     * @param requestId          RequestId
     * @param exceptionClass     最外层异常类
     * @param exceptionMessage   最外层异常消息
     * @param rootCauseException 根因异常类
     * @param rootCauseMessage   根因异常消息
     * @param businessClass      首个业务栈帧类名
     * @param businessMethod     首个业务栈帧方法名
     * @param businessLine       首个业务栈帧行号
     * @param normalizedMessage  归一化消息
     * @param simplifiedStack    简化调用栈
     */
    public RepresentativeEvent(long eventId, long fileRecordId, LocalDateTime logTime,
            String matchType, String locationMode, long startLine, boolean truncated,
            String threadName, String traceId, String tid, String requestId,
            String exceptionClass, String exceptionMessage, String rootCauseException,
            String rootCauseMessage, String businessClass, String businessMethod,
            Integer businessLine, String normalizedMessage, String simplifiedStack) {
        this(eventId, fileRecordId, logTime, matchType, locationMode, startLine, truncated,
                threadName, traceId, tid, requestId, exceptionClass, exceptionMessage,
                rootCauseException, rootCauseMessage, businessClass, businessMethod,
                businessLine, normalizedMessage, simplifiedStack, null, false);
    }

    public RepresentativeEvent(long eventId, long fileRecordId, LocalDateTime logTime,
            String matchType, String locationMode, long startLine, boolean truncated,
            String threadName, String traceId, String tid, String requestId,
            String exceptionClass, String exceptionMessage, String rootCauseException,
            String rootCauseMessage, String businessClass, String businessMethod,
            Integer businessLine, String normalizedMessage, String simplifiedStack,
            String sampleContent, boolean sampleContentTruncated) {
        this.eventId = eventId;
        this.fileRecordId = fileRecordId;
        this.logTime = logTime;
        this.matchType = matchType;
        this.locationMode = locationMode;
        this.startLine = startLine;
        this.truncated = truncated;
        this.threadName = threadName;
        this.traceId = traceId;
        this.tid = tid;
        this.requestId = requestId;
        this.exceptionClass = exceptionClass;
        this.exceptionMessage = exceptionMessage;
        this.rootCauseException = rootCauseException;
        this.rootCauseMessage = rootCauseMessage;
        this.businessClass = businessClass;
        this.businessMethod = businessMethod;
        this.businessLine = businessLine;
        this.normalizedMessage = normalizedMessage;
        this.simplifiedStack = simplifiedStack;
        this.sampleContent = sampleContent;
        this.sampleContentTruncated = sampleContentTruncated;
    }

}
