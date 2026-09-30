package io.github.badfisher.ailog.bootstrap.config;

import java.time.ZoneId;

import io.github.badfisher.ailog.application.ai.InfoContextEnricher;
import io.github.badfisher.ailog.application.config.InfoContextProperties;
import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.domain.config.LogModuleConfigRepository;
import io.github.badfisher.ailog.ingestion.local.StreamingLogEventReader;
import io.github.badfisher.ailog.parser.header.CommonLogHeaderParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared parsing format and timezone for ERROR samples and INFO lookup. */
@Configuration
@EnableConfigurationProperties(InfoContextProperties.class)
public class InfoContextConfiguration {

    @Bean
    public InfoContextEnricher infoContextEnricher(LogModuleConfigRepository modules,
            LogSyncProperties files, InfoContextProperties limits, StreamingLogEventReader reader,
            @Value("${badfisher.timezone:Asia/Shanghai}") String timezone,
            @Value("${badfisher.log-pattern:}") String pattern) {
        ZoneId zone = ZoneId.of(timezone);
        return new InfoContextEnricher(modules, files, limits, reader,
                new CommonLogHeaderParser(zone, pattern), zone);
    }
}