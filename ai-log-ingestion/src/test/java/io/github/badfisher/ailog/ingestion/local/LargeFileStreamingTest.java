package io.github.badfisher.ailog.ingestion.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.badfisher.ailog.domain.log.LogEvent;
import io.github.badfisher.ailog.ingestion.sync.LogSyncException;
import io.github.badfisher.ailog.ingestion.sync.SyncErrorCode;

class LargeFileStreamingTest {

    private static final int BUFFER_SIZE = 65536;

    @TempDir
    Path root;

    @Test
    void emitsWithoutRetainingWholeFile() throws Exception {
        Path ready = Files.createDirectories(root.resolve("ready/error")).resolve("large.log");
        StringBuilder data = new StringBuilder();
        for (int index = 0; index < 10000; index++) {
            data.append("sample-prod@192.0.2.7 || 2026-08-18 10:00:00,001 [worker][TID:t-")
                    .append(index).append("] ERROR com.badfisher.Service.run(10) - failed\n")
                    .append("java.lang.RuntimeException: boom\n\tat com.badfisher.Service.run(Service.java:10)\n");
        }
        Files.write(ready, data.toString().getBytes(StandardCharsets.UTF_8));
        AtomicLong count = new AtomicLong();
        new StreamingLogEventReader().read(ready, event -> count.incrementAndGet());
        assertThat(count.get()).isEqualTo(10000L);
    }

    @Test
    void preservesEventBoundariesAndByteOffsetsAcrossReadBuffers() throws Exception {
        String firstEvent = header("00:00:00,001", "first-")
                + repeat('a', BUFFER_SIZE)
                + "\r\n\tat com.badfisher.Service.run(Service.java:10)\r\n";
        String secondEvent = header("00:00:01,002", "second");
        byte[] content = (firstEvent + secondEvent).getBytes(StandardCharsets.UTF_8);
        Path source = root.resolve("cross-buffer.log");
        Files.write(source, content);
        List<LogEvent> events = new ArrayList<LogEvent>();

        new StreamingLogEventReader(131072, 262144).read(source, events::add);

        long secondStart = firstEvent.getBytes(StandardCharsets.UTF_8).length;
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getStartLine()).isEqualTo(1L);
        assertThat(events.get(0).getEndLine()).isEqualTo(2L);
        assertThat(events.get(0).getStartByte()).isZero();
        assertThat(events.get(0).getEndByte()).isEqualTo(secondStart);
        assertThat(events.get(0).getContent()).doesNotContain("\r");
        assertThat(events.get(1).getStartLine()).isEqualTo(3L);
        assertThat(events.get(1).getEndLine()).isEqualTo(3L);
        assertThat(events.get(1).getStartByte()).isEqualTo(secondStart);
        assertThat(events.get(1).getEndByte()).isEqualTo(content.length);
    }

    @Test
    void keepsOriginalOffsetsWhenLineExceedsConfiguredLimit() throws Exception {
        String oversizedEvent = header("00:00:00,001", "oversized-")
                + repeat('b', 2048) + "\n";
        String secondEvent = header("00:00:01,002", "second");
        byte[] content = (oversizedEvent + secondEvent).getBytes(StandardCharsets.UTF_8);
        Path source = root.resolve("oversized-line.log");
        Files.write(source, content);
        List<LogEvent> events = new ArrayList<LogEvent>();

        new StreamingLogEventReader(1024, 16384).read(source, events::add);

        long secondStart = oversizedEvent.getBytes(StandardCharsets.UTF_8).length;
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getContent().length()).isEqualTo(1024);
        assertThat(events.get(0).isTruncated()).isTrue();
        assertThat(events.get(1).isTruncated()).isFalse();
        assertThat(events.get(0).getEndByte()).isEqualTo(secondStart);
        assertThat(events.get(1).getStartByte()).isEqualTo(secondStart);
        assertThat(events.get(1).getEndByte()).isEqualTo(content.length);
    }

    @Test
    void preservesConsumerFailureInsteadOfReportingMissingFile() throws Exception {
        Path source = root.resolve("consumer.log");
        Files.write(source, header("00:00:00,001", "error").getBytes(StandardCharsets.UTF_8));
        IllegalStateException failure = new IllegalStateException("数据库写入失败");

        assertThatThrownBy(() -> new StreamingLogEventReader(1024, 16384).read(source, event -> {
            throw failure;
        })).isSameAs(failure);
    }

    @Test
    void preservesConsumerFailureWhenNextHeaderFlushesPreviousEvent() throws Exception {
        String content = header("00:00:00,001", "first") + "\n"
                + header("00:00:01,002", "second");
        Path source = root.resolve("consumer-header-flush.log");
        Files.write(source, content.getBytes(StandardCharsets.UTF_8));
        IllegalStateException failure = new IllegalStateException("首个事件持久化失败");

        assertThatThrownBy(() -> new StreamingLogEventReader(1024, 16384)
                .read(source, event -> {
                    throw failure;
                })).isSameAs(failure);
    }

    @Test
    void dropsOnlyIncompleteUtf8SuffixAndPreservesPhysicalOffsets() throws Exception {
        for (String character : new String[] {"中", "😀"}) {
            String prefix = repeat('x', 1023);
            byte[] input = (prefix + character + "尾部").getBytes(StandardCharsets.UTF_8);
            Path source = root.resolve("utf8.log");
            Files.write(source, input);
            List<LogEvent> events = new ArrayList<LogEvent>();

            new StreamingLogEventReader(1024, 16384).read(source, events::add);

            assertThat(events).hasSize(1);
            assertThat(events.get(0).getContent()).isEqualTo(prefix);
            assertThat(events.get(0).isTruncated()).isTrue();
            assertThat(events.get(0).getEndByte()).isEqualTo(input.length);
        }
    }

    @Test
    void reportsCorruptGzipAsReadFailure() throws Exception {
        Path source = root.resolve("corrupt.log.gz");
        Files.write(source, "not-gzip".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new StreamingLogEventReader(1024, 16384).read(source, event -> { }))
                .isInstanceOfSatisfying(LogSyncException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(SyncErrorCode.LOCAL_FILE_READ_FAILED));
    }

    private static String header(String time, String message) {
        return "sample-prod@192.0.2.7 || 2026-08-18 " + time
                + " [worker][TID:t-1] ERROR com.badfisher.Service.run(10) - " + message;
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
