package io.github.badfisher.ailog.parser.header;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 Badfisher 生产日志 Header，并按配置时区生成绝对时间。 */
public final class ProductionLogHeaderParser implements LogHeaderParser {

    /** 生产日志首行格式：实例 || 时间 [线程][TID:xxx] 或 [线程] [xxx] 级别 类.方法(行号) - 消息。 */
    private static final Pattern HEADER = Pattern.compile(
            "^(.+?)\\s*\\|\\|\\s*(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3}) "
                    + "\\[([^]]+)]\\s*\\[(?:TID:)?([^]]*)] "
                    + "(TRACE|DEBUG|INFO|WARN|ERROR) "
                    + "([\\w.$]+)\\.([\\w$<>]+)\\((\\d+)\\) - ?(.*)$");

    /** 时间字段格式。 */
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

    /** 日志时间所在时区。 */
    private final ZoneId zoneId;

    /** 视为无效的 TID 值集合，统一归一化为 {@code null}。 */
    private final Set<String> invalidTraceValues;

    /**
     * 构造解析器。
     *
     * @param zoneId             日志时间时区
     * @param invalidTraceValues 视为无效的 TID 值集合；内部归一化为小写并防御性拷贝
     */
    public ProductionLogHeaderParser(ZoneId zoneId, Set<String> invalidTraceValues) {
        this.zoneId = zoneId;
        Set<String> normalizedValues = new HashSet<>();
        for (String value : invalidTraceValues) {
            normalizedValues.add(value == null ? "" : value.trim().toLowerCase());
        }
        this.invalidTraceValues = Collections.unmodifiableSet(normalizedValues);
    }

    @Override
    public boolean matches(String line) {
        return line != null && HEADER.matcher(line).matches();
    }

    @Override
    public LogHeader parse(String line) {
        Matcher matcher = HEADER.matcher(line);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a supported production log header");
        }
        String tid = normalizeTraceValue(matcher.group(4));
        return new LogHeader(matcher.group(1).trim(),
                LocalDateTime.parse(matcher.group(2), FORMAT).atZone(zoneId).toInstant(),
                matcher.group(3), tid, matcher.group(5), matcher.group(6), matcher.group(7),
                Integer.valueOf(matcher.group(8)), matcher.group(9));
    }

    private String normalizeTraceValue(String value) {
        String trimmedValue = value.trim();
        return invalidTraceValues.contains(trimmedValue.toLowerCase()) ? null : trimmedValue;
    }
}
