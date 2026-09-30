package io.github.badfisher.ailog.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link TimeWindowSplitter} 单元测试：验证自然日按 15 分钟切分为 96 个半开窗口。
 */
class TimeWindowSplitterTest {

    @Test
    void splitsNaturalDayIntoHalfOpenWindows() {
        List<TimeWindow> w = TimeWindowSplitter.split(LocalDate.of(2026, 8, 9), Duration.ofMinutes(15), ZoneId.of("Asia/Shanghai"));
        assertThat(w).hasSize(96);
        assertThat(w.get(0).getStartInclusive()).isEqualTo(Instant.parse("2026-08-08T16:00:00Z"));
        assertThat(w.get(95).getEndExclusive()).isEqualTo(Instant.parse("2026-08-09T16:00:00Z"));
    }
}
