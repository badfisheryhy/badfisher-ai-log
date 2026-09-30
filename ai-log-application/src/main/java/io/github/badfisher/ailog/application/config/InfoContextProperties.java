package io.github.badfisher.ailog.application.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits apply per AI evidence snapshot, across all its samples and INFO files. */
@Getter
@Setter
@ConfigurationProperties(prefix = "badfisher.info-context")
public class InfoContextProperties {

    private boolean enabled = true;
    private int maxEvents = 20;
    private int maxCharacters = 8192;
    private int windowSeconds = 120;
    private int maxFiles = 20;
    private long maxScannedBytes = 64L * 1024 * 1024;
}