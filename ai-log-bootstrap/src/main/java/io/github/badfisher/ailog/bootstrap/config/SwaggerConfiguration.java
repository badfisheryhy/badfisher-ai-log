package io.github.badfisher.ailog.bootstrap.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI description for authenticated backend endpoints. */
@Configuration
public class SwaggerConfiguration {

    @Bean
    public OpenAPI apiDescription() {
        return new OpenAPI()
                .info(new Info().title("badfisher-ai-log").version("0.1.0")
                        .description("Log parsing, AI analysis and issue governance APIs"))
                .components(new Components().addSecuritySchemes("basicAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}