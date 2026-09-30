package io.github.badfisher.ailog.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps persisted evidence serialization stable while the web layer uses Jackson 3. */
@Configuration
public class JsonConfiguration {

    @Bean
    public ObjectMapper evidenceObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}