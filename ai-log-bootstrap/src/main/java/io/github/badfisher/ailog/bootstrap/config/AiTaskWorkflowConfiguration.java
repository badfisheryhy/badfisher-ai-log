package io.github.badfisher.ailog.bootstrap.config;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.github.badfisher.ailog.analysis.ai.AiEvidenceBuilder;
import io.github.badfisher.ailog.analysis.ai.EvidenceHashGenerator;
import io.github.badfisher.ailog.application.ai.AiEvidenceSanitizer;
import io.github.badfisher.ailog.application.ai.AiReportUploadService;
import io.github.badfisher.ailog.application.ai.AiTaskReportMarkdownRenderer;
import io.github.badfisher.ailog.application.ai.AiTaskJobService;
import io.github.badfisher.ailog.application.ai.AiTaskBlameEnrichmentService;
import io.github.badfisher.ailog.application.tool.ObjectStorageTool;
import io.github.badfisher.ailog.bootstrap.integration.ai.FunctionCallingAnalysisProperties;
import io.github.badfisher.ailog.bootstrap.thread.MdcTaskDecorator;
import io.github.badfisher.ailog.domain.ai.AiAnalysisContract;
import io.github.badfisher.ailog.domain.ai.AiAnalysisProviderRegistry;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.domain.ai.AiTaskBlameRepository;
import io.github.badfisher.ailog.domain.ai.GitBlameTool;
import io.github.badfisher.ailog.domain.ai.AiTaskRepository;
import io.github.badfisher.ailog.parser.security.SensitiveLogSanitizer;
import io.github.badfisher.ailog.persistence.ai.MybatisPlusAiTaskRepository;
import io.github.badfisher.ailog.persistence.ai.MybatisPlusAiTaskBlameRepository;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiCallAttemptMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskItemMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogManagementOperationMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;

/** AI 调用、AI Task OSS 报告上传和执行恢复的组件装配。 */
@Configuration
@EnableConfigurationProperties(AiAnalysisProperties.class)
public class AiTaskWorkflowConfiguration {

    @Bean
    AiTaskPlan aiTaskPlan(AiAnalysisProperties properties,
            FunctionCallingAnalysisProperties functionCallingProperties) {
        if (!properties.isEnabled()) {
            return AiTaskPlan.disabled();
        }
        validateLimits(properties);
        AiAnalysisProperties.ProviderProperties provider = properties.getProviders()
                .get(properties.getDefaultProvider());
        if (provider == null) {
            throw new IllegalStateException("Default AI provider configuration is missing");
        }
        AiAnalysisProperties.ModelProperties model = provider.getModels()
                .get(properties.getDefaultModel());
        if (model == null) {
            throw new IllegalStateException("Default AI model configuration is missing");
        }
        // 在领取任务前阻止 Provider 与 Prompt 版本不匹配，避免消耗存量 Item 的调用次数。
        String requiredPromptVersion = functionCallingProperties.isEnabled()
                ? AiAnalysisContract.FUNCTION_CALLING_PROMPT_VERSION
                : AiAnalysisContract.PROMPT_VERSION;
        if (!requiredPromptVersion.equals(properties.getPromptVersion())) {
            throw new IllegalStateException("AI prompt-version must be " + requiredPromptVersion
                    + " when function-calling.enabled=" + functionCallingProperties.isEnabled()
                    + "; configured value: " + properties.getPromptVersion());
        }
        String parametersJson = "{\"maxTokens\":" + model.getMaxTokens() + "}";
        return new AiTaskPlan(true, properties.getDefaultProvider(),
                properties.getModelProfileCode(), properties.getDefaultModel(),
                properties.getPromptVersion(), properties.getSanitizerVersion(),
                parametersJson, properties.getSelectionLimit(), provider.getMaxAttempts(),
                provider.getRetryBackoffMillis());
    }

    @Bean
    @ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
    AiTaskRepository aiTaskRepository(AiLogAiTaskMapper tasks,
            AiLogAiTaskItemMapper items, AiLogAiCallAttemptMapper attempts,
            AiLogManagementOperationMapper operations,
            AiLogIssueGroupMapper issues, AiLogErrorEventMapper events,
            ObjectMapper objectMapper, AiTaskPlan aiTaskPlan,
            AiLogIssueGroupGovernanceMapper governance) {
        return new MybatisPlusAiTaskRepository(
                tasks, items, attempts, operations, issues, events, objectMapper, aiTaskPlan, governance);
    }

    @Bean
    @ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
    AiTaskBlameRepository aiTaskBlameRepository(AiLogAiTaskItemMapper items,
            AiLogErrorEventMapper events) {
        return new MybatisPlusAiTaskBlameRepository(items, events);
    }

    @Bean
    @ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
    AiTaskBlameEnrichmentService aiTaskBlameEnrichmentService(
            AiTaskBlameRepository repository, GitBlameTool gitBlameTool) {
        return new AiTaskBlameEnrichmentService(repository, gitBlameTool);
    }

    @Bean(name = "aiAnalysisTaskExecutor")
    @ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
    ThreadPoolTaskExecutor aiAnalysisTaskExecutor(AiAnalysisProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerThreads());
        executor.setMaxPoolSize(properties.getWorkerThreads());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("ai-analysis-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
    ScheduledExecutorService aiLeaseRenewalScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-lease-renewal");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
    AiTaskJobService aiTaskJobService(AiTaskRepository repository,
            AiAnalysisProviderRegistry registry,
            AiTaskBlameEnrichmentService blameEnrichmentService,
            @Qualifier("aiAnalysisTaskExecutor") Executor executor,
            ScheduledExecutorService aiLeaseRenewalScheduler,
            AiAnalysisProperties properties,
            io.github.badfisher.ailog.application.ai.InfoContextEnricher contextEnricher,
            @Value("${spring.application.name:badfisher-ai-log}") String applicationName) {
        return new AiTaskJobService(repository, registry, new AiEvidenceBuilder(),
                new AiEvidenceSanitizer(new SensitiveLogSanitizer()),
                new EvidenceHashGenerator(), blameEnrichmentService,
                executor, aiLeaseRenewalScheduler,
                properties.getDispatchLimit(),
                properties.getSampleLimit(), properties.getWorkerThreads(),
                properties.getLeaseSeconds(),
                applicationName + ":" + UUID.randomUUID().toString(), Clock.systemDefaultZone(), contextEnricher);
    }

    @Bean
    @ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
    AiTaskReportMarkdownRenderer aiTaskReportMarkdownRenderer() {
        return new AiTaskReportMarkdownRenderer();
    }

    @Bean
    @ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
    AiReportUploadService aiReportUploadService(AiTaskRepository repository,
            ObjectProvider<ObjectStorageTool> objectStorageTools,
            AiTaskReportMarkdownRenderer renderer) {
        return new AiReportUploadService(repository, objectStorageTools.getIfAvailable(),
                renderer, Clock.systemDefaultZone());
    }

    private static void validateLimits(AiAnalysisProperties properties) {
        if (properties.getSelectionLimit() < 1
                || properties.getSelectionLimit() > AiTaskPlan.HARD_SELECTION_LIMIT) {
            throw new IllegalStateException("AI selection-limit must be within [1,200]");
        }
        if (properties.getDispatchLimit() < 1
                || properties.getDispatchLimit() > AiTaskPlan.HARD_DISPATCH_LIMIT) {
            throw new IllegalStateException("AI dispatch-limit must be within [1,50]");
        }
        if (properties.getSampleLimit() < 1
                || properties.getSampleLimit() > AiTaskPlan.HARD_SAMPLE_LIMIT) {
            throw new IllegalStateException("AI sample-limit must be within [1,3]");
        }
        if (properties.getWorkerThreads() < 1 || properties.getQueueCapacity() < 1
                || properties.getLeaseSeconds() < 1) {
            throw new IllegalStateException(
                    "AI worker-threads, queue-capacity and lease-seconds must be positive");
        }
    }
}
