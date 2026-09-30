package io.github.badfisher.ailog.application.ai;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import io.github.badfisher.ailog.application.config.InfoContextProperties;
import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.application.plan.LocalLogFileResolver;
import io.github.badfisher.ailog.application.path.LogPathAccessPolicy;
import io.github.badfisher.ailog.domain.ai.AiIssueEvidence;
import io.github.badfisher.ailog.domain.ai.InfoContext;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.domain.config.LogModuleConfigRepository;
import io.github.badfisher.ailog.domain.log.LogEvent;
import io.github.badfisher.ailog.domain.log.LogLocationMode;
import io.github.badfisher.ailog.ingestion.local.StreamingLogEventReader;
import io.github.badfisher.ailog.parser.header.LogHeaderParser;
import io.github.badfisher.ailog.parser.stream.LogEventAssembler;

/** Selects INFO on demand for AI samples; no full INFO database or unbounded memory index. */
public final class InfoContextEnricher implements UnaryOperator<AiIssueEvidence> {

    private final LogModuleConfigRepository modules;
    private final LogSyncProperties files;
    private final InfoContextProperties limits;
    private final StreamingLogEventReader reader;
    private final LogHeaderParser parser;
    private final ZoneId zone;

    public InfoContextEnricher(LogModuleConfigRepository modules, LogSyncProperties files,
            InfoContextProperties limits, StreamingLogEventReader reader,
            LogHeaderParser parser, ZoneId zone) {
        if (limits.getMaxEvents() < 1 || limits.getMaxEvents() > 100
                || limits.getMaxCharacters() < 1 || limits.getMaxCharacters() > 65536
                || limits.getWindowSeconds() < 1 || limits.getWindowSeconds() > 3600
                || limits.getMaxFiles() < 1 || limits.getMaxFiles() > 100
                || limits.getMaxScannedBytes() < 1) {
            throw new IllegalArgumentException("INFO context limits are outside the supported range");
        }
        this.modules = modules;
        this.files = files;
        this.limits = limits;
        this.reader = reader;
        this.parser = parser;
        this.zone = zone;
    }

    @Override
    public AiIssueEvidence apply(AiIssueEvidence evidence) {
        if (!limits.isEnabled()) {
            return evidence;
        }
        List<Target> targets = targets(evidence);
        if (targets.isEmpty()) {
            return evidence.withInfoContext(List.of(), "NO_CORRELATION");
        }
        LogModuleConfig module = modules.findEnabledModules(evidence.getEnvironment(), evidence.getSystemCode())
                .stream().filter(value -> value.getModuleCode().equals(evidence.getModuleCode()))
                .findFirst().orElse(null);
        if (module == null) {
            return evidence.withInfoContext(List.of(), "MODULE_UNAVAILABLE");
        }
        Selection selection = new Selection(targets);
        try {
            Path directory = LogPathAccessPolicy.requireDirectory(Path.of(module.getRemoteDirectory()),
                    files.getAllowedLogRoots());
            Set<Path> sources = new LinkedHashSet<>();
            Set<LocalDate> dates = new HashSet<>();
            for (Target target : targets) {
                dates.add(target.time().atZone(zone).toLocalDate());
                dates.add(target.time().minusSeconds(limits.getWindowSeconds()).atZone(zone).toLocalDate());
                dates.add(target.time().plusSeconds(limits.getWindowSeconds()).atZone(zone).toLocalDate());
            }
            for (LocalDate date : dates.stream().sorted().toList()) {
                sources.addAll(LocalLogFileResolver.resolve(directory,
                        files.getInfoFilePatterns(), date, module.getLogFilePrefix(), limits.getMaxFiles()));
            }
            if (sources.size() > limits.getMaxFiles()) {
                return evidence.withInfoContext(List.of(), "FILE_LIMIT_REACHED");
            }
            for (Path source : sources) {
                if (selection.scanned >= limits.getMaxScannedBytes()) {
                    throw new LimitReached();
                }
                Path verified = LogPathAccessPolicy.requireFile(source, files.getAllowedLogRoots());
                selection.scanned += reader.read(verified, limits.getMaxScannedBytes() - selection.scanned,
                        event -> selection.accept(source, event));
            }
            String status = sources.isEmpty() ? "FILES_UNAVAILABLE"
                    : selection.context.isEmpty() ? "NO_MATCH" : "MATCHED";
            return evidence.withInfoContext(selection.context, status);
        } catch (LimitReached | StreamingLogEventReader.ReadLimitExceededException exception) {
            return evidence.withInfoContext(selection.context, "LIMIT_REACHED");
        } catch (RuntimeException exception) {
            // Missing optional files must be visible as incomplete evidence, never invented context.
            return evidence.withInfoContext(selection.context, "READ_FAILED");
        }
    }

    private List<Target> targets(AiIssueEvidence evidence) {
        List<Target> targets = new ArrayList<>();
        for (AiIssueEvidence.EvidenceSample sample : evidence.getSamples()) {
            if (sample.getLogTime() == null || sample.getSampleContent() == null) {
                continue;
            }
            LogEventAssembler assembler = new LogEventAssembler(65536, parser);
            List<LogEvent> parsed = new ArrayList<>();
            String firstLine = sample.getSampleContent().split("\\R", 2)[0];
            assembler.accept(firstLine, 1, 0, firstLine.length(), LogLocationMode.PLAIN_BYTE_OFFSET, parsed::add);
            assembler.finish(parsed::add);
            if (!parsed.isEmpty()) {
                Set<String> ids = correlationIds(parsed.getFirst());
                if (!ids.isEmpty()) {
                    targets.add(new Target(sample.getEventId(), sample.getLogTime().atZone(zone).toInstant(), ids));
                }
            }
        }
        return targets;
    }

    private static Set<String> correlationIds(LogEvent event) {
        Set<String> result = new HashSet<>();
        addId(result, "trace:", event.getTid());
        addId(result, "trace:", event.getTraceId());
        addId(result, "request:", event.getRequestId());
        return result;
    }

    private static void addId(Set<String> ids, String kind, String value) {
        if (value != null && !value.isBlank()
                && !Set.of("-", "null", "NULL", "N/A", "Ignored_Trace").contains(value)) {
            ids.add(kind + value);
        }
    }

    private final class Selection {
        private final List<Target> targets;
        private final List<InfoContext> context = new ArrayList<>();
        private int characters;
        private long scanned;

        private Selection(List<Target> targets) {
            this.targets = targets;
        }

        private void accept(Path source, LogEvent event) {
            if (!"INFO".equals(event.getLevel()) || event.getTimestamp() == null) {
                return;
            }
            Set<String> ids = correlationIds(event);
            for (Target target : targets) {
                Duration distance = Duration.between(target.time(), event.getTimestamp()).abs();
                if (distance.compareTo(Duration.ofSeconds(limits.getWindowSeconds())) > 0
                        || java.util.Collections.disjoint(ids, target.ids())) {
                    continue;
                }
                if (context.size() >= limits.getMaxEvents() || characters >= limits.getMaxCharacters()) {
                    throw new LimitReached();
                }
                String text = event.getContent();
                int retained = Math.min(text.length(), limits.getMaxCharacters() - characters);
                context.add(new InfoContext(target.eventId(), event.getTimestamp(),
                        source.getFileName().toString(), event.getStartLine(), text.substring(0, retained)));
                characters += retained;
                if (retained < text.length()) {
                    throw new LimitReached();
                }
                break;
            }
        }
    }

    private record Target(long eventId, Instant time, Set<String> ids) {
    }

    private static final class LimitReached extends RuntimeException {
    }
}