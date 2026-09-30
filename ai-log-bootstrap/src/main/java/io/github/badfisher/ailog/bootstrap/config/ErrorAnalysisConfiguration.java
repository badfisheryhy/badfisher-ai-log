package io.github.badfisher.ailog.bootstrap.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.badfisher.ailog.analysis.error.ErrorLogClassifier;
import io.github.badfisher.ailog.analysis.error.ErrorLogStatisticsService;
import io.github.badfisher.ailog.analysis.module.DefaultErrorAnalysisModule;
import io.github.badfisher.ailog.analysis.module.ErrorAnalysisModuleRegistry;
import io.github.badfisher.ailog.analysis.module.ProjectErrorAnalysisModule;
import io.github.badfisher.ailog.bootstrap.service.ConfiguredProjectAnalysisService;

/**
 * ERROR 分类与统计组件装配。
 */
@Configuration
public class ErrorAnalysisConfiguration {

    /**
     * 注册 ERROR 日志分类器。
     */
    @Bean
    ErrorLogClassifier errorLogClassifier() {
        return new ErrorLogClassifier();
    }

    /**
     * 注册 ERROR 统计服务，依赖 {@link ErrorLogClassifier}。
     */
    @Bean
    ErrorLogStatisticsService errorLogStatisticsService(ErrorLogClassifier classifier) {
        return new ErrorLogStatisticsService(classifier);
    }

    /**
     * 注册默认项目 ERROR 分析模块，依赖 {@link ErrorLogStatisticsService}。
     */
    @Bean
    ProjectErrorAnalysisModule defaultErrorAnalysisModule(ErrorLogStatisticsService service) {
        return new DefaultErrorAnalysisModule(service);
    }

    /**
     * 注册 ERROR 分析模块注册表，收集全部项目级分析模块。
     */
    @Bean
    ErrorAnalysisModuleRegistry errorAnalysisModuleRegistry(List<ProjectErrorAnalysisModule> modules) {
        return new ErrorAnalysisModuleRegistry(modules);
    }

    /**
     * 注册按项目配置选择分析模块的服务，依赖 {@link ErrorAnalysisModuleRegistry}。
     */
    @Bean
    ConfiguredProjectAnalysisService configuredProjectAnalysisService(ErrorAnalysisModuleRegistry registry) {
        return new ConfiguredProjectAnalysisService(registry);
    }
}
