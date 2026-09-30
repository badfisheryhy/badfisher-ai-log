package io.github.badfisher.ailog.loki.client;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.badfisher.ailog.domain.log.LogReference;
import io.github.badfisher.ailog.domain.log.RawLogEntry;

import lombok.RequiredArgsConstructor;

/**
 * Loki {@code query_range} 响应解析器：流式解析 JSON 响应并转换为 {@link RawLogEntry} 列表。
 * <p>
 * 基于 Jackson streaming API 逐 token 读取，避免整包响应体在内存中构建字符串或对象树；
 * 响应非 success、结构缺失或格式错误时抛出 {@link LokiDataAccessException}。
 */
@RequiredArgsConstructor
public final class LokiResponseParser {

    private final ObjectMapper mapper;

    /** 用于回填 {@link LogReference#getService()} 的标签名。 */
    private final String serviceLabel;

    /**
     * 流式解析 Loki 响应体。
     *
     * @param input 响应 JSON 输入流
     * @return 不可变的原始日志条目列表
     * @throws LokiDataAccessException 响应非 success 或结构非法时抛出
     */
    public List<RawLogEntry> parse(InputStream input) {
        try {
            try (JsonParser parser = mapper.getFactory().createParser(input)) {
                return parseRoot(parser);
            }
        } catch (LokiDataAccessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LokiDataAccessException("Unable to parse Loki response", ex);
        }
    }

    /** 解析顶层对象，校验 status 并收集 data.result 中的日志条目。 */
    private List<RawLogEntry> parseRoot(JsonParser parser) throws IOException {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw new LokiDataAccessException("Loki response must be a JSON object");
        }
        List<RawLogEntry> out = new ArrayList<>();
        boolean success = false;
        boolean hasData = false;
        JsonToken token = parser.nextToken();
        while (token != JsonToken.END_OBJECT) {
            if (token == null) {
                throw new LokiDataAccessException("Incomplete Loki response");
            }
            String fieldName = parser.getCurrentName();
            if ("status".equals(fieldName)) {
                parser.nextToken();
                if (!"success".equals(parser.getValueAsString())) {
                    throw new LokiDataAccessException("Loki returned non-success response");
                }
                success = true;
            } else if ("data".equals(fieldName)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new LokiDataAccessException("Loki data must be an object");
                }
                parseData(parser, out);
                hasData = true;
            } else {
                token = skipValue(parser);
                continue;
            }
            token = parser.nextToken();
        }
        if (!success || !hasData) {
            throw new LokiDataAccessException("Loki response is missing status or data");
        }
        return Collections.unmodifiableList(out);
    }

    /** 解析 data 对象，读取 result 数组。 */
    private void parseData(JsonParser parser, List<RawLogEntry> out) throws IOException {
        boolean hasResult = false;
        JsonToken token = parser.nextToken();
        while (token != JsonToken.END_OBJECT) {
            if (token == null) {
                throw new LokiDataAccessException("Incomplete Loki data");
            }
            String fieldName = parser.getCurrentName();
            if ("result".equals(fieldName)) {
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    throw new LokiDataAccessException("Loki result must be an array");
                }
                parseResultArray(parser, out);
                hasResult = true;
            } else {
                token = skipValue(parser);
                continue;
            }
            token = parser.nextToken();
        }
        if (!hasResult) {
            throw new LokiDataAccessException("Loki data is missing result");
        }
    }

    /** 解析 result 数组中的每个 stream。 */
    private void parseResultArray(JsonParser parser, List<RawLogEntry> out) throws IOException {
        JsonToken token = parser.nextToken();
        while (token != JsonToken.END_ARRAY) {
            parseStream(parser, out);
            token = parser.nextToken();
        }
    }

    /** 解析单个 stream 对象：读取标签与值序列。 */
    private void parseStream(JsonParser parser, List<RawLogEntry> out) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw new LokiDataAccessException("Malformed Loki stream");
        }
        Map<String, String> labels = new HashMap<>();
        List<RawLogEntry> streamEntries = new ArrayList<>();
        JsonToken token = parser.nextToken();
        while (token != JsonToken.END_OBJECT) {
            String fieldName = parser.getCurrentName();
            if ("stream".equals(fieldName)) {
                if (parser.nextToken() == JsonToken.START_OBJECT) {
                    parseLabels(parser, labels);
                }
            } else if ("values".equals(fieldName)) {
                if (parser.nextToken() == JsonToken.START_ARRAY) {
                    parseValues(parser, labels, streamEntries);
                }
            } else {
                token = skipValue(parser);
                continue;
            }
            token = parser.nextToken();
        }
        // JSON 字段没有先后顺序；values 在 stream 前时补齐不可变条目的标签和引用。
        for (RawLogEntry entry : streamEntries) {
            if (entry.getLabels().equals(labels)) {
                out.add(entry);
            } else {
                out.add(new RawLogEntry(entry.getTimestamp(), labels, entry.getLine(),
                        new LogReference(entry.getTimestamp(),
                                serviceLabel == null ? null : labels.get(serviceLabel))));
            }
        }
    }

    /** 解析 stream 标签对象。 */
    private void parseLabels(JsonParser parser, Map<String, String> labels) throws IOException {
        JsonToken token = parser.nextToken();
        while (token != JsonToken.END_OBJECT) {
            String name = parser.getCurrentName();
            parser.nextToken();
            labels.put(name, parser.getValueAsString());
            token = parser.nextToken();
        }
    }

    /** 解析 values 数组，逐条构造原始日志条目。 */
    private void parseValues(JsonParser parser, Map<String, String> labels, List<RawLogEntry> out)
            throws IOException {
        JsonToken token = parser.nextToken();
        while (token != JsonToken.END_ARRAY) {
            if (token != JsonToken.START_ARRAY) {
                throw new LokiDataAccessException("Malformed Loki value");
            }
            parser.nextToken();
            long nanos = Long.parseLong(parser.getValueAsString());
            parser.nextToken();
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw new LokiDataAccessException("Malformed Loki value");
            }
            String line = parser.getValueAsString();
            parser.nextToken();
            Instant timestamp = Instant.ofEpochSecond(nanos / 1000000000L, nanos % 1000000000L);
            out.add(new RawLogEntry(timestamp, labels, line,
                    new LogReference(timestamp, serviceLabel == null ? null : labels.get(serviceLabel))));
            token = parser.nextToken();
        }
    }

    /** 跳过当前字段值（含嵌套容器），返回后续的字段名或 END 标记。 */
    private static JsonToken skipValue(JsonParser parser) throws IOException {
        JsonToken value = parser.nextToken();
        if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
            parser.skipChildren();
        }
        // 返回未知字段之后的 token，不能把其内部 END_OBJECT 当成外层对象结束。
        return parser.nextToken();
    }
}
