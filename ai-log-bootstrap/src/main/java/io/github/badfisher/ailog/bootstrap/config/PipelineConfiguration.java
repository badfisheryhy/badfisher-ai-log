package io.github.badfisher.ailog.bootstrap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.badfisher.ailog.application.pipeline.PipelineService;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.domain.pipeline.PipelineRepository;

/** 流水线应用端口装配。 */
@Configuration
public class PipelineConfiguration {

    @Bean
    PipelineService pipelineService(PipelineRepository repository, AiTaskPlan plan) {
        return new PipelineService(repository, plan);
    }
}
