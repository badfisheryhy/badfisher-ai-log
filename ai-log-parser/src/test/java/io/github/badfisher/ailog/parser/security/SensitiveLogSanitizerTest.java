package io.github.badfisher.ailog.parser.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link SensitiveLogSanitizer} 单元测试：验证外发脱敏只作用于副本，不影响原文。
 */
class SensitiveLogSanitizerTest {

    @Test
    void removesSecretsOnlyFromExternalCopy() {
        String raw = "Authorization: Bearer abc.def password=secret 手机 13800138000 user@example.com";
        String external = new SensitiveLogSanitizer().sanitizeForExternal(raw);
        assertThat(raw).contains("abc.def", "13800138000");
        assertThat(external).doesNotContain("abc.def", "secret", "13800138000", "user@example.com").contains("<:REDACTED:>");
    }

    @Test
    void masksSnakeCaseAndCompoundSecretTokens() {
        String raw = "access_token=t1 refresh_token=t2 api_key=k1 appSecret=s1 passwd=p1";
        String external = new SensitiveLogSanitizer().sanitizeForExternal(raw);
        assertThat(external).doesNotContain("t1", "t2", "k1", "s1", "p1");
        assertThat(external).contains("<:REDACTED:>");
    }

    @Test
    void sanitizesNestedJsonAndPreservesNonSensitiveTypes() throws Exception {
        String raw = "{\"apiKey\":\"json-key\","
                + "\"nested\":[{\"appSecret\":\"json-secret\",\"refresh_token\":\"refresh-value\"}],"
                + "\"authorization\":\"Bearer header-value\","
                + "\"cookie\":\"session=cookie-value; other=second-cookie\","
                + "\"code\":503,\"retryable\":true,\"total_tokens\":123,"
                + "\"phone\":13800138000,\"email\":\"tester@example.com\"}";
        String external = new SensitiveLogSanitizer().sanitizeForExternal(raw);
        JsonNode json = new ObjectMapper().readTree(external);

        assertThat(external).doesNotContain("json-key", "json-secret", "refresh-value",
                "header-value", "cookie-value", "second-cookie", "13800138000", "tester@example.com");
        assertThat(json.path("apiKey").asText()).isEqualTo("<:REDACTED:>");
        assertThat(json.path("code").isInt()).isTrue();
        assertThat(json.path("code").asInt()).isEqualTo(503);
        assertThat(json.path("retryable").asBoolean()).isTrue();
        assertThat(json.path("total_tokens").asInt()).isEqualTo(123);
        assertThat(raw).contains("json-key", "header-value");
    }

    @Test
    void sanitizesEncodedKeysAndJsonInsideStringValues() throws Exception {
        String raw = "{\"api\\u004bey\":\"unicode-secret\","
                + "\"message\":\"{\\\"password\\\":\\\"embedded-secret\\\"}\"}";
        JsonNode json = new ObjectMapper().readTree(
                new SensitiveLogSanitizer().sanitizeForExternal(raw));

        assertThat(json.path("apiKey").asText()).isEqualTo("<:REDACTED:>");
        JsonNode message = new ObjectMapper().readTree(json.path("message").asText());
        assertThat(message.path("password").asText()).isEqualTo("<:REDACTED:>");
    }

    @Test
    void sanitizesMixedLogsAndUnterminatedQuotedValues() {
        SensitiveLogSanitizer sanitizer = new SensitiveLogSanitizer();
        String raw = "ERROR payload={\"api-key\":\"key with spaces\","
                + "\"password\":\"escaped\\\"value\",\"code\":500} tail=visible";
        String external = sanitizer.sanitizeForExternal(raw);

        assertThat(external).doesNotContain("key with spaces", "escaped", "value\"");
        assertThat(external).contains("\"code\":500", "tail=visible");
        assertThat(sanitizer.sanitizeForExternal("ERROR password=\"unfinished secret"))
                .doesNotContain("unfinished", "secret");
        assertThat(sanitizer.sanitizeForExternal("ERROR password={\"nested\":\"secret\""))
                .doesNotContain("nested", "secret");
    }

    @Test
    void consumesFullAuthorizationAndCookieValues() {
        String raw = "Authorization: Bearer bearer-value\n"
                + "Proxy-Authorization: Basic basic-value\n"
                + "Cookie: session=cookie-first; other=cookie-second\n"
                + "standalone Bearer standalone-value\nstatus=503";
        String external = new SensitiveLogSanitizer().sanitizeForExternal(raw);

        assertThat(external).doesNotContain("bearer-value", "basic-value",
                "cookie-first", "cookie-second", "standalone-value");
        assertThat(external).contains("status=503");
    }

    @Test
    void retainsTrailingLogAndIsIdempotent() {
        SensitiveLogSanitizer sanitizer = new SensitiveLogSanitizer();
        String raw = "{\"status\":500} password=tail-secret trace=trace-1";
        String external = sanitizer.sanitizeForExternal(raw);

        assertThat(external).contains("\"status\":500", "trace=trace-1");
        assertThat(external).doesNotContain("tail-secret");
        assertThat(sanitizer.sanitizeForExternal(external)).isEqualTo(external);
        assertThat(sanitizer.sanitizeForExternal(null)).isNull();
        assertThat(sanitizer.sanitizeForExternal("")).isEmpty();
    }

    @Test
    void sharesSourceFieldRecognitionWithoutMaskingTokenMetrics() {
        SensitiveLogSanitizer sanitizer = new SensitiveLogSanitizer();

        assertThat(sanitizer.containsSensitiveSource("String refresh_token = \"value\";")).isTrue();
        assertThat(sanitizer.containsSensitiveSource("String passwd = \"value\";")).isTrue();
        assertThat(sanitizer.containsSensitiveSource("System.out.println(apiKey);")).isTrue();
        assertThat(sanitizer.containsSensitiveSource("Bearer source-token")).isTrue();
        assertThat(sanitizer.containsSensitiveSource("int total_tokens = 100;")).isFalse();
        assertThat(sanitizer.containsSensitiveSource("int maxTokens = 2000;")).isFalse();
        assertThat(sanitizer.containsSensitiveSource(null)).isFalse();
    }
}
