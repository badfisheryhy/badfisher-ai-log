package io.github.badfisher.ailog.loki.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.badfisher.ailog.domain.log.RawLogEntry;

/**
 * {@link LokiResponseParser} 单元测试：验证 stream 响应解析、纳秒时间戳还原与服务标签回填。
 */
class LokiResponseParserTest {

    @Test
    void parsesStreamResponse() {
        String json = "{\"status\":\"success\",\"data\":{\"result\":[{\"stream\":{\"service\":\"sample-service\"},"
                + "\"values\":[[\"1785984000123456789\",\"ERROR test\"]]}]}}";
        List<RawLogEntry> x = new LokiResponseParser(new ObjectMapper(), "service")
                .parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertThat(x).hasSize(1);
        assertThat(x.get(0).getTimestamp()).isEqualTo(Instant.ofEpochSecond(1785984000L, 123456789));
        assertThat(x.get(0).getReference().getService()).isEqualTo("sample-service");
    }

    @Test
    void skipsNestedUnknownFieldsWithoutEndingTheirParentObject() {
        String json = "{\"metadata\":{\"nested\":{\"ignored\":true}},\"status\":\"success\","
                + "\"data\":{\"stats\":{\"summary\":{}},\"result\":[{"
                + "\"extra\":[{\"ignored\":true}],\"stream\":{\"service\":\"sample-service\"},"
                + "\"values\":[[\"1785984000123456789\",\"ERROR one\"]]}]}}";

        List<RawLogEntry> entries = parse(json);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getLine()).isEqualTo("ERROR one");
        assertThat(entries.get(0).getReference().getService()).isEqualTo("sample-service");
    }

    @Test
    void preservesLabelsWhenValuesAppearBeforeTheStreamObject() {
        String json = "{\"status\":\"success\",\"data\":{\"result\":[{"
                + "\"values\":[[\"1785984000123456789\",\"ERROR one\"]],"
                + "\"stream\":{\"service\":\"sample-service\",\"env\":\"test\"}}]}}";

        List<RawLogEntry> entries = parse(json);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getLabels()).containsEntry("env", "test");
        assertThat(entries.get(0).getReference().getService()).isEqualTo("sample-service");
    }

    @Test
    void rejectsMissingOrMalformedRequiredResponseSections() {
        for (String json : new String[] {
                "{}", "{\"data\":{\"result\":[]}}", "{\"status\":\"success\"}",
                "{\"status\":\"success\",\"data\":null}",
                "{\"status\":\"success\",\"data\":{}}",
                "{\"status\":\"success\",\"data\":{\"result\":{}}}"}) {
            assertThatThrownBy(() -> parse(json))
                    .as("Malformed response: %s", json)
                    .isInstanceOf(LokiDataAccessException.class);
        }
    }

    @Test
    void acceptsAnExplicitlyEmptySuccessfulResult() {
        assertThat(parse("{\"status\":\"success\",\"data\":{\"result\":[]}}")).isEmpty();
    }

    private static List<RawLogEntry> parse(String json) {
        return new LokiResponseParser(new ObjectMapper(), "service")
                .parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
