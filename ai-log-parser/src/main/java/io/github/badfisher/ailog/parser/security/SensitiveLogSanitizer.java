package io.github.badfisher.ailog.parser.security;

import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * 外发及审计副本的统一脱敏器，内部原文不变。
 *
 * <p>完整 JSON 按字段处理，兼容嵌套对象、数组和字符串内的 JSON；
 * 混合日志或截断 JSON 按敏感赋值处理。源码调用方复用字段识别，仍整行遮蔽。</p>
 */
public final class SensitiveLogSanitizer {

    private static final String REDACTED = "<:REDACTED:>";
    private static final int MAX_JSON_DEPTH = 32;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** 支持常见前缀、大小写和分隔符；不把 maxTokens、tokenUsage 等计量字段当成凭证。 */
    private static final String SENSITIVE_NAME = "[a-z0-9_.-]*(?:authorization"
            + "|(?:api|access|private)[-_.]?key(?:[-_.]?id)?"
            + "|secret|password|passwd|cookie|token)";
    private static final Pattern FIELD_NAME = Pattern.compile("(?i)" + SENSITIVE_NAME);
    private static final Pattern FIELD_REFERENCE = Pattern.compile(
            "(?i)(?<![a-z0-9_.-])" + SENSITIVE_NAME + "(?![a-z0-9_.-])");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(?<![a-z0-9_.-])([\"']?(" + SENSITIVE_NAME + ")[\"']?\\s*[:=]\\s*)");
    private static final Pattern AUTH_VALUE = Pattern.compile(
            "(?i)(\\b(?:Bearer|Basic)[\\t ]+)[^\\s,;\"'<>]+");

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");

    /** 生成脱敏副本；null 保持为 null，失败不得降级为原文外发。 */
    public String sanitizeForExternal(String raw) {
        return sanitize(raw, 0);
    }

    /** 仅判断源码敏感行，不改变缩进、行号或调用方的整行遮蔽策略。 */
    public boolean containsSensitiveSource(String line) {
        return line != null
                && (FIELD_REFERENCE.matcher(line).find() || AUTH_VALUE.matcher(line).find());
    }

    private String sanitize(String raw, int depth) {
        if (raw == null) {
            return null;
        }
        if (depth > MAX_JSON_DEPTH) {
            return REDACTED;
        }
        JsonNode json = readCompleteJson(raw);
        if (json == null) {
            return sanitizeText(raw);
        }
        JsonNode sanitized = sanitizeNode(json, depth);
        try {
            return JSON_MAPPER.writeValueAsString(sanitized);
        } catch (JsonProcessingException ex) {
            // 不携带可能回显内容的序列化异常，调用网关会终止本次外发。
            throw new IllegalStateException("Unable to serialize sanitized content");
        }
    }

    /** 只接受完整 JSON，禁止 readTree 忽略尾部日志导致内容被静默丢弃。 */
    private JsonNode readCompleteJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "{[\"".indexOf(trimmed.charAt(0)) < 0) {
            return null;
        }
        try (JsonParser parser = JSON_MAPPER.getFactory().createParser(raw)) {
            JsonNode node = JSON_MAPPER.readTree(parser);
            return parser.nextToken() == null ? node : null;
        } catch (IOException ex) {
            // 混合或截断日志不是结构化解析失败的业务异常，继续执行文本脱敏。
            return null;
        }
    }

    private JsonNode sanitizeNode(JsonNode node, int depth) {
        if (depth > MAX_JSON_DEPTH) {
            return TextNode.valueOf(REDACTED);
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = FIELD_NAME.matcher(field.getKey()).matches()
                        ? TextNode.valueOf(REDACTED) : sanitizeNode(field.getValue(), depth + 1);
                object.set(field.getKey(), value);
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, sanitizeNode(array.get(index), depth + 1));
            }
        } else if (node.isTextual()) {
            return TextNode.valueOf(sanitize(node.asText(), depth + 1));
        } else if (node.isNumber()) {
            String value = node.asText();
            String sanitized = sanitizePersonalData(value);
            if (!value.equals(sanitized)) {
                return TextNode.valueOf(sanitized);
            }
        }
        return node;
    }

    private String sanitizeText(String raw) {
        Matcher assignments = ASSIGNMENT.matcher(raw);
        StringBuilder result = new StringBuilder(raw.length());
        int cursor = 0;
        while (assignments.find(cursor)) {
            int start = assignments.end();
            result.append(raw, cursor, start);
            char first = start < raw.length() ? raw.charAt(start) : '\0';
            if (first == '"' || first == '\'') {
                result.append(first).append(REDACTED).append(first);
            } else {
                result.append(REDACTED);
            }
            cursor = valueEnd(raw, start, assignments.group(2));
        }
        result.append(raw, cursor, raw.length());
        String text = AUTH_VALUE.matcher(result).replaceAll("$1" + REDACTED);
        return sanitizePersonalData(text);
    }

    /** 引号值完整消费转义字符；无法完整解析的敏感容器或引号值遮蔽剩余片段。 */
    private static int valueEnd(String raw, int start, String fieldName) {
        if (start >= raw.length()) {
            return raw.length();
        }
        char first = raw.charAt(start);
        if (first == '"' || first == '\'') {
            for (int index = start + 1; index < raw.length(); index++) {
                char current = raw.charAt(index);
                if (current == '\\') {
                    index++;
                } else if (current == first) {
                    return index + 1;
                }
            }
            return raw.length();
        }
        if (first == '{' || first == '[') {
            return raw.length();
        }
        if (fieldName.toLowerCase(Locale.ROOT).endsWith("cookie")) {
            int end = start;
            while (end < raw.length() && raw.charAt(end) != '\r' && raw.charAt(end) != '\n') {
                end++;
            }
            return end;
        }
        int end = tokenEnd(raw, start);
        String scheme = raw.substring(start, end);
        if ("Bearer".equalsIgnoreCase(scheme) || "Basic".equalsIgnoreCase(scheme)) {
            int credential = end;
            while (credential < raw.length()
                    && (raw.charAt(credential) == ' ' || raw.charAt(credential) == '\t')) {
                credential++;
            }
            return tokenEnd(raw, credential);
        }
        return end;
    }

    private static int tokenEnd(String raw, int start) {
        int end = start;
        while (end < raw.length() && !Character.isWhitespace(raw.charAt(end))
                && ",;\"'}]&".indexOf(raw.charAt(end)) < 0) {
            end++;
        }
        return end;
    }

    private static String sanitizePersonalData(String raw) {
        String text = PHONE.matcher(raw).replaceAll(REDACTED);
        text = EMAIL.matcher(text).replaceAll(REDACTED);
        text = ID.matcher(text).replaceAll(REDACTED);
        return CARD.matcher(text).replaceAll(REDACTED);
    }
}
