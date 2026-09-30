package io.github.badfisher.ailog.application.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import io.github.badfisher.ailog.analysis.ai.EvidenceHashGenerator;
import io.github.badfisher.ailog.application.config.InfoContextProperties;
import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence;
import io.github.badfisher.ailog.domain.ai.SanitizedAiEvidence;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.domain.config.LogModuleConfigRepository;
import io.github.badfisher.ailog.ingestion.local.StreamingLogEventReader;
import io.github.badfisher.ailog.parser.header.CommonLogHeaderParser;
import io.github.badfisher.ailog.parser.security.SensitiveLogSanitizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InfoContextEnricherTest {

    @TempDir
    Path directory;

    @Test
    void selectsOnlyMatchingInfoWithinWindowAndKeepsErrorSamplesUnchanged() throws Exception {
        Files.writeString(directory.resolve("service-info.log"),
                info("10:00:01", "trace-other", "unrelated")
                + info("09:00:00", "trace-1", "too-old")
                + info("10:00:02", "trace-1", "accepted")
                + info("10:00:03", "trace-1", "wrong-level").replace("INFO", "ERROR"));
        AiIssueEvidence original = evidence("trace-1");
        AiIssueEvidence result = enricher(new InfoContextProperties()).apply(original);
        assertThat(result.getInfoContextStatus()).isEqualTo("MATCHED");
        assertThat(result.getInfoContext()).hasSize(1);
        assertThat(result.getInfoContext().getFirst().content()).contains("accepted");
        assertThat(result.getSamples()).containsExactlyElementsOf(original.getSamples());
        assertThat(result.getOccurrenceCount()).isEqualTo(5);
        assertThat(original.getInfoContext()).isEmpty();
    }

    @Test
    void capsSelectedEventsAndCharacters() throws Exception {
        Files.writeString(directory.resolve("info.log"),
                info("10:00:01", "trace-1", "one") + info("10:00:02", "trace-1", "two"));
        InfoContextProperties limits = new InfoContextProperties();
        limits.setMaxEvents(1);
        AiIssueEvidence result = enricher(limits).apply(evidence("trace-1"));
        assertThat(result.getInfoContext()).hasSize(1);
        assertThat(result.getInfoContextStatus()).isEqualTo("LIMIT_REACHED");
        limits.setMaxCharacters(25);
        result = enricher(limits).apply(evidence("trace-1"));
        assertThat(result.getInfoContext().getFirst().content()).hasSize(25);
    }

    @Test
    void noCorrelationNeverFallsBackToTimeOrThreadAlone() throws Exception {
        Files.writeString(directory.resolve("info.log"), info("10:00:01", "trace-1", "accepted"));
        AiIssueEvidence result = enricher(new InfoContextProperties()).apply(evidence("-"));
        assertThat(result.getInfoContext()).isEmpty();
        assertThat(result.getInfoContextStatus()).isEqualTo("NO_CORRELATION");
    }

    @Test
    void contextIsSanitizedBeforeHashingAndSending() throws Exception {
        Files.writeString(directory.resolve("info.log"),
                info("10:00:01", "trace-1", "password=synthetic-secret"));
        AiIssueEvidence enriched = enricher(new InfoContextProperties()).apply(evidence("trace-1"));
        AiEvidenceSanitizer sanitizer = new AiEvidenceSanitizer(new SensitiveLogSanitizer());
        SanitizedAiEvidence safe = sanitizer.sanitize(enriched);
        assertThat(safe.getEvidence().getInfoContext().getFirst().content())
                .doesNotContain("synthetic-secret");
        EvidenceHashGenerator hashes = new EvidenceHashGenerator();
        assertThat(hashes.hash(safe, "p1", "s1"))
                .isNotEqualTo(hashes.hash(sanitizer.sanitize(evidence("trace-1")), "p1", "s1"));
    }

    @Test
    void decodedBudgetIncludesDiscardedLongLinesAcrossGzipFiles() throws Exception {
        byte[] ignored = ("unstructured " + "x".repeat(100000) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (String name : List.of("a-info.log.gz", "b-info.log.gz")) {
            try (var output = new java.util.zip.GZIPOutputStream(Files.newOutputStream(directory.resolve(name)))) {
                output.write(ignored);
            }
        }
        Files.writeString(directory.resolve("z-info.log"), info("10:00:01", "trace-1", "beyond budget"));
        InfoContextProperties limits = new InfoContextProperties();
        limits.setMaxScannedBytes(150000);
        AiIssueEvidence result = enricher(limits).apply(evidence("trace-1"));
        assertThat(result.getInfoContext()).isEmpty();
        assertThat(result.getInfoContextStatus()).isEqualTo("LIMIT_REACHED");
    }

    @Test
    void requestIdDoesNotMatchAnUnrelatedTraceIdWithTheSameText() throws Exception {
        Files.writeString(directory.resolve("info.log"),
                info("10:00:01", "trace-1", "other request").replace("traceId=", "requestId="));
        AiIssueEvidence result = enricher(new InfoContextProperties()).apply(evidence("trace-1"));
        assertThat(result.getInfoContext()).isEmpty();
        assertThat(result.getInfoContextStatus()).isEqualTo("NO_MATCH");
    }
    @Test
    void deniesInfoReadWhenDirectoryIsOutsideConfiguredRoots() throws Exception {
        Files.writeString(directory.resolve("info.log"), info("10:00:01", "trace-1", "must not be read"));
        Path otherRoot = Files.createDirectory(directory.resolve("allowed"));
        AiIssueEvidence result = enricher(new InfoContextProperties(), List.of(otherRoot.toString()))
                .apply(evidence("trace-1"));
        assertThat(result.getInfoContext()).isEmpty();
        assertThat(result.getInfoContextStatus()).isEqualTo("READ_FAILED");
    }

    private InfoContextEnricher enricher(InfoContextProperties limits) {
        return enricher(limits, List.of(directory.toString()));
    }

    private InfoContextEnricher enricher(InfoContextProperties limits, List<String> allowedRoots) {
        LogModuleConfig module = new LogModuleConfig();
        module.setModuleCode("service");
        module.setRemoteDirectory(directory.toString());
        module.setLogFilePrefix("service");
        LogModuleConfigRepository repository = mock(LogModuleConfigRepository.class);
        when(repository.findEnabledModules("test", "demo")).thenReturn(List.of(module));
        ZoneId zone = ZoneId.of("UTC");
        CommonLogHeaderParser parser = new CommonLogHeaderParser(zone, null);
        LogSyncProperties files = new LogSyncProperties();
        files.setAllowedLogRoots(allowedRoots);
        return new InfoContextEnricher(repository, files, limits,
                new StreamingLogEventReader(65536, 65536, parser), parser, zone);
    }

    private static String info(String time, String trace, String message) {
        return "2026-09-27 " + time + " [main] INFO com.example.Service - traceId="
                + trace + " " + message + "\n";
    }

    private static AiIssueEvidence evidence(String trace) {
        LocalDateTime time = LocalDateTime.of(2026, 9, 27, 10, 0);
        AiIssueEvidence.EvidenceSample sample = new AiIssueEvidence.EvidenceSample(1, time,
                "STRICT_ERROR", false, "IllegalStateException", "failed", null, null,
                "failed", "stack", "2026-09-27 10:00:00 [main] ERROR com.example.Service - traceId="
                        + trace + " failed", false);
        return new AiIssueEvidence(1, "test", "demo", "service", "fingerprint", "v1", "CODE",
                "UNKNOWN", "IllegalStateException", null, "com.example.Service", "run", "failed",
                null, 5, time, time, "failed", "stack", List.of(sample));
    }
}
