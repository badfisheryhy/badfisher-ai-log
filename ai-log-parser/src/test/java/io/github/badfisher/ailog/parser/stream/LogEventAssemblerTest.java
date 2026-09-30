package io.github.badfisher.ailog.parser.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.badfisher.ailog.domain.log.LogEvent;
import io.github.badfisher.ailog.domain.log.LogLocationMode;

class LogEventAssemblerTest {
    @Test
    void assemblesCausedByAndSuppressedUntilNextHeader() {
        LogEventAssembler assembler = new LogEventAssembler(16384);
        List<LogEvent> events = new ArrayList<LogEvent>();
        assembler.accept("sample-prod@192.0.2.7 || 2026-08-18 10:00:00,001 [http-1][TID:t1] ERROR com.badfisher.Service.run(10) - failed", 1, 0, 120,
                LogLocationMode.PLAIN_BYTE_OFFSET, events::add);
        assembler.accept("java.lang.IllegalStateException: top", 2, 70, 110,
                LogLocationMode.PLAIN_BYTE_OFFSET, events::add);
        assembler.accept("Suppressed: java.io.IOException: ignored", 3, 110, 155,
                LogLocationMode.PLAIN_BYTE_OFFSET, events::add);
        assembler.accept("Caused by: java.sql.SQLException: root", 4, 155, 200,
                LogLocationMode.PLAIN_BYTE_OFFSET, events::add);
        assembler.accept("sample-prod@192.0.2.7 || 2026-08-18 10:00:01,001 [http-1][TID:t1] INFO com.badfisher.Service.run(11) - done", 5, 200, 320,
                LogLocationMode.PLAIN_BYTE_OFFSET, events::add);
        assembler.finish(events::add);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getContent()).contains("Suppressed:", "Caused by:");
        assertThat(events.get(0).getTid()).isEqualTo("t1");
        assertThat(events.get(0).getLoggerLine()).isEqualTo(10);
        assertThat(events.get(0).getEndLine()).isEqualTo(4L);
        assertThat(events.get(0).getStartByte()).isZero();
    }

    @Test
    void boundsOversizedFirstHeaderWithoutChangingPhysicalCoordinates() {
        String firstLine = "instance || 2026-08-18 10:00:00,001 [worker][TID:t1] "
                + "ERROR com.badfisher.Service.run(10) - "
                + String.join("", Collections.nCopies(5000, "x"));
        long firstEndByte = 200L + firstLine.length() + 1L;
        LogEventAssembler assembler = new LogEventAssembler(4096);
        List<LogEvent> events = new ArrayList<LogEvent>();
        assembler.accept(firstLine, 10L, 200L, firstEndByte,
                LogLocationMode.PLAIN_BYTE_OFFSET, events::add);
        assembler.accept("continued", 11L, firstEndByte, firstEndByte + 10L,
                LogLocationMode.PLAIN_BYTE_OFFSET, events::add);
        assembler.finish(events::add);

        assertThat(events).hasSize(1);
        LogEvent event = events.get(0);
        assertThat(event.getContent()).isEqualTo(firstLine.substring(0, 4096));
        assertThat(event.isTruncated()).isTrue();
        assertThat(event.getLoggerLine()).isEqualTo(10);
        assertThat(event.getStartLine()).isEqualTo(10L);
        assertThat(event.getEndLine()).isEqualTo(11L);
        assertThat(event.getStartByte()).isEqualTo(200L);
        assertThat(event.getEndByte()).isEqualTo(firstEndByte + 10L);
    }

    @Test
    void boundsOversizedFirstLineWithoutAHeader() {
        String firstLine = String.join("", Collections.nCopies(5000, "x"));
        LogEventAssembler assembler = new LogEventAssembler(4096);
        List<LogEvent> events = new ArrayList<LogEvent>();
        assembler.accept(firstLine, 1L, 0L, 5000L,
                LogLocationMode.PLAIN_BYTE_OFFSET, events::add);
        assembler.finish(events::add);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getContent()).isEqualTo(firstLine.substring(0, 4096));
        assertThat(events.get(0).isTruncated()).isTrue();
        assertThat(events.get(0).getEndByte()).isEqualTo(5000L);
    }
}
