package io.github.badfisher.ailog.parser.header;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonLogHeaderParserTest {

    private final CommonLogHeaderParser parser = new CommonLogHeaderParser(ZoneId.of("UTC"), null);

    @Test
    void parsesSpringBootAndClassicLogbackWithoutCompanyPrefix() {
        LogHeader boot = parser.parse("2026-09-27T10:00:00.123Z ERROR 42 --- [demo] [main] com.example.Service : failed");
        assertThat(boot.getLevel()).isEqualTo("ERROR");
        assertThat(boot.getTimestamp()).isEqualTo(Instant.parse("2026-09-27T10:00:00.123Z"));
        assertThat(boot.getThreadName()).isEqualTo("main");
        LogHeader classic = parser.parse("2026-09-27 10:00:00,123 [worker-1] INFO com.example.Service - accepted");
        assertThat(classic.getLevel()).isEqualTo("INFO");
        assertThat(classic.getMessage()).isEqualTo("accepted");
    }

    @Test
    void parsesJsonLinesAndPreservesInfoSeverity() {
        LogHeader header = parser.parse("{\"@timestamp\":\"2026-09-27T18:00:00+08:00\","
                + "\"level\":\"info\",\"thread_name\":\"main\",\"trace_id\":\"trace-1\","
                + "\"logger_name\":\"com.example.Service\",\"message\":\"accepted\"}");
        assertThat(header.getTimestamp()).isEqualTo(Instant.parse("2026-09-27T10:00:00Z"));
        assertThat(header.getTid()).isEqualTo("trace-1");
        assertThat(header.getLevel()).isEqualTo("INFO");
    }

    @Test
    void usesConfiguredZoneOnlyWhenTimestampHasNoOffset() {
        CommonLogHeaderParser local = new CommonLogHeaderParser(ZoneId.of("Asia/Shanghai"), null);
        LogHeader header = local.parse("2026-09-27 18:00:00 [main] ERROR com.example.Service - failed");
        assertThat(header.getTimestamp()).isEqualTo(Instant.parse("2026-09-27T10:00:00Z"));
    }

    @Test
    void supportsCustomNamedGroupsAndDoesNotRecognizeStackAsHeader() {
        CommonLogHeaderParser custom = new CommonLogHeaderParser(ZoneId.of("UTC"),
                "^(?<timestamp>[^|]+)\\|(?<level>INFO|ERROR)\\|(?<traceId>[^|]+)\\|(?<message>.*)$");
        assertThat(custom.parse("2026-09-27T10:00:00Z|ERROR|t-1|failed").getTid()).isEqualTo("t-1");
        assertThat(custom.matches("    at com.example.Service.run(Service.java:42)")).isFalse();
        assertThatThrownBy(() -> new CommonLogHeaderParser(ZoneId.of("UTC"), ".*"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}