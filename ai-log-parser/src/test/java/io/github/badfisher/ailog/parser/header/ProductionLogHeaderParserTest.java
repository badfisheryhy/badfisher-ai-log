package io.github.badfisher.ailog.parser.header;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

class ProductionLogHeaderParserTest {
    private static final String HEADER = "sample-prod@192.0.2.7 || "
            + "2026-08-01 03:09:07,171 [worker-1][TID:abc] ERROR "
            + "com.badfisher.Service.run(68) - failed";

    @Test
    void parsesSyntheticPrefixedHeader() {
        LogHeader header = parser().parse(HEADER);
        assertThat(header.getInstance()).isEqualTo("sample-prod@192.0.2.7");
        assertThat(header.getTimestamp()).isEqualTo(Instant.parse("2026-07-31T19:09:07.171Z"));
        assertThat(header.getThreadName()).isEqualTo("worker-1");
        assertThat(header.getTid()).isEqualTo("abc");
        assertThat(header.getLoggerClass()).isEqualTo("com.badfisher.Service");
        assertThat(header.getLoggerMethod()).isEqualTo("run");
        assertThat(header.getLoggerLine()).isEqualTo(68);
    }

    @Test
    void parsesAdvertisingHeaderWithPlainTraceBracket() {
        String line = "sample-prod@zone-192.0.2.10/198.51.100.11 || "
                + "2026-08-18 07:12:01,325 [ordered-22] [1234567890123456789] ERROR "
                + "com.example.analytics.app.service.AdvertisingTargetingService.updateBid(742) "
                + "- Please try again later.";

        LogHeader header = parser().parse(line);

        assertThat(header.getInstance())
                .isEqualTo("sample-prod@zone-192.0.2.10/198.51.100.11");
        assertThat(header.getThreadName()).isEqualTo("ordered-22");
        assertThat(header.getTid()).isEqualTo("1234567890123456789");
        assertThat(header.getLoggerClass()).isEqualTo(
                "com.example.analytics.app.service.AdvertisingTargetingService");
        assertThat(header.getLoggerMethod()).isEqualTo("updateBid");
        assertThat(header.getLoggerLine()).isEqualTo(742);
    }

    @Test
    void invalidTraceValuesAreNeverExposedAsCorrelationKeys() {
        for (String value : Arrays.asList("N/A", "Ignored_Trace", "NULL", "null", "-", "")) {
            String headerLine = HEADER.replace("TID:abc", "TID:" + value);
            assertThat(parser().parse(headerLine).getTid()).as(value).isNull();
        }
    }

    @Test
    void configuredZoneDoesNotDependOnJvmDefault() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Instant utcJvm = parser().parse(HEADER).getTimestamp();
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            Instant shanghaiJvm = parser().parse(HEADER).getTimestamp();
            assertThat(utcJvm).isEqualTo(shanghaiJvm)
                    .isEqualTo(Instant.parse("2026-07-31T19:09:07.171Z"));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private static ProductionLogHeaderParser parser() {
        return new ProductionLogHeaderParser(ZoneId.of("Asia/Shanghai"),
                new HashSet<String>(Arrays.asList(
                        "N/A", "Ignored_Trace", "NULL", "null", "-", "")));
    }
}
