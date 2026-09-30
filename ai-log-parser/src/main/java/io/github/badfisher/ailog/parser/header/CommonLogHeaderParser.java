package io.github.badfisher.ailog.parser.header;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Common Logback/Spring Boot text, JSON Lines, and an optional named-group pattern. */
public final class CommonLogHeaderParser implements LogHeaderParser {

    private static final String TIME = "(?<timestamp>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}"
            + "(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?)";
    private static final String LEVEL = "(?<level>TRACE|DEBUG|INFO|WARN|ERROR|FATAL)";
    private static final Pattern BOOT = Pattern.compile("^" + TIME + "\\s+" + LEVEL
            + "\\s+(?:\\d+\\s+---\\s+)?(?:\\[[^]]*]\\s+)?\\[(?<thread>[^]]*)]\\s+"
            + "(?<logger>[\\w.$]+)\\s*[:\\-]\\s*(?<message>.*)$");
    private static final Pattern CLASSIC = Pattern.compile("^" + TIME
            + "\\s+\\[(?<thread>[^]]*)]\\s+" + LEVEL
            + "\\s+(?<logger>[\\w.$]+)\\s*[:\\-]\\s*(?<message>.*)$");
    private static final Set<String> INVALID_IDS = Set.of("", "-", "null", "NULL", "N/A", "Ignored_Trace");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ZoneId zone;
    private final Pattern custom;
    private final ProductionLogHeaderParser prefixed;

    public CommonLogHeaderParser(ZoneId zone, String customPattern) {
        this.zone = java.util.Objects.requireNonNull(zone, "Log timezone is required");
        custom = customPattern == null || customPattern.isBlank() ? null : Pattern.compile(customPattern);
        if (custom != null && (!customPattern.contains("(?<timestamp>")
                || !customPattern.contains("(?<level>") || !customPattern.contains("(?<message>"))) {
            throw new IllegalArgumentException("Log pattern requires timestamp, level and message named groups");
        }
        prefixed = new ProductionLogHeaderParser(zone, INVALID_IDS);
    }

    @Override
    public boolean matches(String line) {
        return line != null && (match(custom, line) != null || prefixed.matches(line)
                || match(BOOT, line) != null || match(CLASSIC, line) != null || json(line) != null);
    }

    @Override
    public LogHeader parse(String line) {
        Matcher match = match(custom, line);
        if (match != null) {
            return textHeader(match);
        }
        if (prefixed.matches(line)) {
            return prefixed.parse(line);
        }
        match = match(BOOT, line);
        if (match == null) {
            match = match(CLASSIC, line);
        }
        if (match != null) {
            return textHeader(match);
        }
        JsonNode node = json(line);
        if (node == null) {
            throw new IllegalArgumentException("Unsupported log header");
        }
        return new LogHeader(value(node, "host", "instance"),
                timestamp(value(node, "@timestamp", "timestamp", "time")),
                value(node, "thread_name", "thread", "threadName"),
                validId(value(node, "trace_id", "traceId", "tid")),
                normalizeLevel(value(node, "level", "log.level", "severity")),
                value(node, "logger_name", "logger", "loggerClass"), null, null,
                value(node, "message", "msg"));
    }

    private LogHeader textHeader(Matcher match) {
        return new LogHeader(group(match, "instance"), timestamp(group(match, "timestamp")),
                group(match, "thread"), validId(group(match, "traceId")),
                normalizeLevel(group(match, "level")), group(match, "logger"),
                group(match, "method"), null, group(match, "message"));
    }

    private Instant timestamp(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace(',', '.').replace(' ', 'T');
        if (normalized.matches(".*[+-]\\d{4}$")) {
            int offsetMinutes = normalized.length() - 2;
            normalized = normalized.substring(0, offsetMinutes) + ":" + normalized.substring(offsetMinutes);
        }
        try {
            return OffsetDateTime.parse(normalized).toInstant();
        } catch (DateTimeParseException exception) {
            try {
                return LocalDateTime.parse(normalized).atZone(zone).toInstant();
            } catch (DateTimeParseException invalidTimestamp) {
                // Keep the event boundary; an unknown timestamp must not match INFO context.
                return null;
            }
        }
    }

    private static Matcher match(Pattern pattern, String line) {
        if (pattern == null || line == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(line);
        return matcher.matches() ? matcher : null;
    }

    private static String group(Matcher matcher, String name) {
        try {
            return matcher.group(name);
        } catch (IllegalArgumentException missingOptionalGroup) {
            return null;
        }
    }

    private static JsonNode json(String line) {
        if (line == null || !line.startsWith("{")) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(line);
            String level = value(node, "level", "log.level", "severity");
            return node.isObject() && level != null
                    && Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")
                            .contains(level.toUpperCase(Locale.ROOT)) ? node : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String value(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isTextual()) {
                return value.textValue();
            }
        }
        return null;
    }

    private static String normalizeLevel(String value) {
        String level = value == null ? "UNKNOWN" : value.toUpperCase(Locale.ROOT);
        return "FATAL".equals(level) ? "ERROR" : level;
    }

    private static String validId(String value) {
        return value == null || INVALID_IDS.contains(value) ? null : value;
    }
}