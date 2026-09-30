package io.github.badfisher.ailog.bootstrap.service;

import java.util.List;

import io.github.badfisher.ailog.analysis.error.ErrorStatistics;
import io.github.badfisher.ailog.analysis.module.DefaultErrorAnalysisModule;
import io.github.badfisher.ailog.analysis.module.ErrorAnalysisModuleRegistry;
import io.github.badfisher.ailog.domain.log.RawLogEntry;

/**
 * 根据项目配置选择 ERROR 分析模块；未配置项目时使用默认模块保持兼容。
 */
public final class ConfiguredProjectAnalysisService {

    private final ErrorAnalysisModuleRegistry registry;

    /**
     * 构造服务。
     *
     * @param registry 分析模块注册表
     */
    public ConfiguredProjectAnalysisService(ErrorAnalysisModuleRegistry registry) {
        this.registry = registry;
    }

    /**
     * 分析指定项目的错误日志。
     *
     * @param projectCode 项目配置编码
     * @param entries     原始日志条目
     * @return 错误统计结果
     */
    public ErrorStatistics analyze(String projectCode, List<RawLogEntry> entries) {
        return registry.required(DefaultErrorAnalysisModule.CODE).analyze(entries);
    }
}
