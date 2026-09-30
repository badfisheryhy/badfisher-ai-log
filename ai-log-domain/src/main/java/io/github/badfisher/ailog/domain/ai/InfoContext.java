package io.github.badfisher.ailog.domain.ai;

import java.time.Instant;

/** One bounded INFO fragment associated with a particular ERROR sample. */
public record InfoContext(long sampleEventId, Instant timestamp, String fileName,
        long startLine, String content) {
}