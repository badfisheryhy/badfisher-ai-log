package io.github.badfisher.ailog.parser.stream;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.badfisher.ailog.domain.log.LogEvent;
import io.github.badfisher.ailog.domain.log.LogLocationMode;
import io.github.badfisher.ailog.parser.header.LogHeader;
import io.github.badfisher.ailog.parser.header.LogHeaderParser;
import io.github.badfisher.ailog.parser.header.ProductionLogHeaderParser;

/** 以合法日志 Header 为唯一事件边界，保留完整异常栈、Cause 和 Suppressed。 */
public final class LogEventAssembler {

    /** 事件内容最小长度限制。 */
    private static final int MIN_EVENT_CHARS = 4096;

    /** 事件内容最大长度限制。 */
    private static final int MAX_EVENT_CHARS = 1048576;

    /** 默认时区。 */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 默认视为无效的 TID 值集合。 */
    private static final Set<String> DEFAULT_INVALID_TRACE_VALUES = new HashSet<>(
            Arrays.asList("N/A", "Ignored_Trace", "NULL", "null", "-", ""));

    private static final Pattern TRACE_ID = Pattern.compile(
            "(?:traceId|trace_id)\"?\\s*[=:]\\s*\"?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUEST_ID = Pattern.compile(
            "(?:requestId|request_id)\"?\\s*[=:]\\s*\"?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    /** 单事件最大字符数。 */
    private final int maxEventChars;

    /** 日志头解析器。 */
    private final LogHeaderParser headerParser;

    /** 当前正在组装的事件。 */
    private EventBuilder currentEvent;

    /**
     * 使用默认生产日志头解析器构造组装器。
     *
     * @param maxEventChars 单事件最大字符数，必须在 {@value #MIN_EVENT_CHARS}-{@value #MAX_EVENT_CHARS} 之间
     */
    public LogEventAssembler(int maxEventChars) {
        this(maxEventChars, new ProductionLogHeaderParser(DEFAULT_ZONE, DEFAULT_INVALID_TRACE_VALUES));
    }

    /**
     * 构造组装器。
     *
     * @param maxEventChars 单事件最大字符数，必须在 {@value #MIN_EVENT_CHARS}-{@value #MAX_EVENT_CHARS} 之间
     * @param headerParser  日志头解析器，不允许为 {@code null}
     */
    public LogEventAssembler(int maxEventChars, LogHeaderParser headerParser) {
        if (maxEventChars < MIN_EVENT_CHARS || maxEventChars > MAX_EVENT_CHARS) {
            throw new IllegalArgumentException(
                    "maxEventChars must be between 4096 and 1048576");
        }
        if (headerParser == null) {
            throw new IllegalArgumentException("headerParser must not be null");
        }
        this.maxEventChars = maxEventChars;
        this.headerParser = headerParser;
    }

    /**
     * 接收一条物理行；只有下一条合法 Header 才会结束当前事件。
     *
     * @param line         日志物理行
     * @param lineNumber   行号
     * @param startByte    起始字节偏移
     * @param endByte      结束字节偏移
     * @param locationMode 日志定位模式
     * @param consumer     事件完成时的消费回调
     */
    public void accept(String line, long lineNumber, long startByte, long endByte,
            LogLocationMode locationMode, Consumer<LogEvent> consumer) {
        accept(line, lineNumber, startByte, endByte, locationMode, consumer, false);
    }

    /** 接收物理行及其截断标记；该标记必须传递到所属 Event。 */
    public void accept(String line, long lineNumber, long startByte, long endByte,
            LogLocationMode locationMode, Consumer<LogEvent> consumer, boolean lineTruncated) {
        if (headerParser.matches(line)) {
            flush(consumer);
            currentEvent = new EventBuilder(lineNumber, startByte, locationMode,
                    headerParser.parse(line), line, maxEventChars);
        } else if (currentEvent == null) {
            currentEvent = new EventBuilder(lineNumber, startByte, locationMode, null,
                    line, maxEventChars);
        } else {
            currentEvent.append(line, maxEventChars);
        }
        currentEvent.endLine = lineNumber;
        currentEvent.endByte = endByte;
        currentEvent.truncated |= lineTruncated;
    }

    /** 结束输入流，将最后一条未完成事件交付给消费者。 */
    public void finish(Consumer<LogEvent> consumer) {
        flush(consumer);
    }

    private void flush(Consumer<LogEvent> consumer) {
        if (currentEvent != null) {
            consumer.accept(currentEvent.build());
            currentEvent = null;
        }
    }

    private static String find(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static final class EventBuilder {
        private final long startLine;
        private final long startByte;
        private final LogLocationMode locationMode;
        private final LogHeader header;
        private final StringBuilder content = new StringBuilder();
        private long endLine;
        private long endByte;
        private boolean truncated;

        private EventBuilder(long startLine, long startByte, LogLocationMode locationMode,
                LogHeader header, String firstLine, int maximumChars) {
            this.startLine = startLine;
            this.startByte = startByte;
            this.locationMode = locationMode;
            this.header = header;
            // 首行也受事件上限约束；物理行号和字节范围仍由 accept 保留。
            content.append(firstLine, 0, Math.min(firstLine.length(), maximumChars));
            truncated = firstLine.length() > maximumChars;
        }

        private void append(String line, int maximumChars) {
            if (content.length() >= maximumChars) {
                truncated = true;
                return;
            }
            String addition = "\n" + line;
            int remaining = maximumChars - content.length();
            if (addition.length() > remaining) {
                content.append(addition, 0, remaining);
                truncated = true;
                return;
            }
            content.append(addition);
        }

        private LogEvent build() {
            String text = content.toString();
            boolean supportsByteOffset = locationMode == LogLocationMode.PLAIN_BYTE_OFFSET;
            return new LogEvent(header == null ? null : header.getTimestamp(),
                    header == null ? "UNKNOWN" : header.getLevel(),
                    header == null ? null : header.getThreadName(), find(TRACE_ID, text),
                    header == null ? null : header.getTid(), find(REQUEST_ID, text),
                    header == null ? null : header.getLoggerClass(),
                    header == null ? null : header.getLoggerMethod(),
                    header == null ? null : header.getLoggerLine(),
                    header == null ? null : header.getMessage(), text, startLine, endLine,
                    supportsByteOffset ? startByte : -1L, supportsByteOffset ? endByte : -1L,
                    locationMode, truncated);
        }
    }
}
