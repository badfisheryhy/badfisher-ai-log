package io.github.badfisher.ailog.domain.log;

import lombok.Getter;

import java.time.Instant;

/** 一条完整日志事件；仅承载当前事件，禁止作为全文件累计容器使用。 */
@Getter
public final class LogEvent {
    /** 日志时间戳。 */
    private final Instant timestamp;

    /** 日志级别。 */
    private final String level;

    /** 线程名。 */
    private final String threadName;

    /** 链路追踪 ID。 */
    private final String traceId;

    /** 事务 ID。 */
    private final String tid;

    /** 请求 ID。 */
    private final String requestId;

    /** 日志来源 Logger 类名；未知时为 {@code null}。 */
    private final String loggerClass;

    /** 日志来源 Logger 方法名；未知时为 {@code null}。 */
    private final String loggerMethod;

    /** 日志来源行号；未知时为 {@code null}。 */
    private final Integer loggerLine;

    /** Header 解析出的业务消息；无合法 Header 时为 {@code null}。 */
    private final String message;

    /** 事件完整文本内容。 */
    private final String content;

    /** 起始行号（含）。 */
    private final long startLine;

    /** 结束行号（含）。 */
    private final long endLine;

    /** 起始字节偏移。 */
    private final long startByte;

    /** 结束字节偏移。 */
    private final long endByte;

    /** 日志定位模式。 */
    private final LogLocationMode locationMode;

    /** 是否因超限被截断。 */
    private final boolean truncated;

    /**
     * 构造无 Logger 定位信息的日志事件。
     *
     * @param timestamp    时间戳
     * @param level        日志级别
     * @param threadName   线程名
     * @param traceId      链路追踪 ID
     * @param tid          事务 ID
     * @param requestId    请求 ID
     * @param content      事件文本内容
     * @param startLine    起始行号
     * @param endLine      结束行号
     * @param startByte    起始字节偏移
     * @param endByte      结束字节偏移
     * @param locationMode 日志定位模式
     * @param truncated    是否截断
     */
    public LogEvent(Instant timestamp, String level, String threadName, String traceId, String tid,
            String requestId, String content, long startLine, long endLine, long startByte, long endByte,
            LogLocationMode locationMode, boolean truncated) {
        this.timestamp = timestamp;
        this.level = level;
        this.threadName = threadName;
        this.traceId = traceId;
        this.tid = tid;
        this.requestId = requestId;
        loggerClass = null;
        loggerMethod = null;
        loggerLine = null;
        message = null;
        this.content = content;
        this.startLine = startLine;
        this.endLine = endLine;
        this.startByte = startByte;
        this.endByte = endByte;
        this.locationMode = locationMode;
        this.truncated = truncated;
    }

    /**
     * 构造带 Logger 定位信息的日志事件。
     *
     * @param timestamp    时间戳
     * @param level        日志级别
     * @param threadName   线程名
     * @param traceId      链路追踪 ID
     * @param tid          事务 ID
     * @param requestId    请求 ID
     * @param loggerClass  日志来源类名
     * @param loggerMethod 日志来源方法名
     * @param loggerLine   日志来源行号
     * @param content      事件文本内容
     * @param startLine    起始行号
     * @param endLine      结束行号
     * @param startByte    起始字节偏移
     * @param endByte      结束字节偏移
     * @param locationMode 日志定位模式
     * @param truncated    是否截断
     */
    public LogEvent(Instant timestamp, String level, String threadName, String traceId, String tid,
            String requestId, String loggerClass, String loggerMethod, Integer loggerLine, String content,
            long startLine, long endLine, long startByte, long endByte, LogLocationMode locationMode,
            boolean truncated) {
        this(timestamp, level, threadName, traceId, tid, requestId, loggerClass, loggerMethod,
                loggerLine, null, content, startLine, endLine, startByte, endByte, locationMode,
                truncated);
    }

    /**
     * 构造带 Header 业务消息和 Logger 定位信息的日志事件。
     *
     * @param timestamp    时间戳
     * @param level        日志级别
     * @param threadName   线程名
     * @param traceId      链路追踪 ID
     * @param tid          事务 ID
     * @param requestId    请求 ID
     * @param loggerClass  日志来源类名
     * @param loggerMethod 日志来源方法名
     * @param loggerLine   日志来源行号
     * @param message      Header 解析出的业务消息
     * @param content      事件完整文本内容
     * @param startLine    起始行号
     * @param endLine      结束行号
     * @param startByte    起始字节偏移
     * @param endByte      结束字节偏移
     * @param locationMode 日志定位模式
     * @param truncated    是否截断
     */
    public LogEvent(Instant timestamp, String level, String threadName, String traceId, String tid,
            String requestId, String loggerClass, String loggerMethod, Integer loggerLine,
            String message, String content, long startLine, long endLine, long startByte,
            long endByte, LogLocationMode locationMode, boolean truncated) {
        this.timestamp = timestamp;
        this.level = level;
        this.threadName = threadName;
        this.traceId = traceId;
        this.tid = tid;
        this.requestId = requestId;
        this.loggerClass = loggerClass;
        this.loggerMethod = loggerMethod;
        this.loggerLine = loggerLine;
        this.message = message;
        this.content = content;
        this.startLine = startLine;
        this.endLine = endLine;
        this.startByte = startByte;
        this.endByte = endByte;
        this.locationMode = locationMode;
        this.truncated = truncated;
    }

    public boolean isError() { return "ERROR".equalsIgnoreCase(level); }
}
