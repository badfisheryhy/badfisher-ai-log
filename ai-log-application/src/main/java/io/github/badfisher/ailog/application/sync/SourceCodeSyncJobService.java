package io.github.badfisher.ailog.application.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import io.github.badfisher.ailog.application.tool.SourceCodeSyncTool;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.domain.config.LogModuleConfigRepository;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/**
 * 按环境串行同步全部启用模块源码的独立任务服务。
 *
 * <p>本服务只负责读取模块配置并调用源码同步端口，不创建日志同步任务，也不修改日志、
 * 解析或 AI 状态。单个模块失败会被记录并继续后续模块，调用方根据最终汇总决定任务
 * 状态。日志同步流程中的单模块源码准备仍作为独立补偿路径保留。</p>
 */
@Slf4j
public final class SourceCodeSyncJobService {

    private final LogModuleConfigRepository repository;
    private final SourceCodeSyncTool sourceCodeSync;

    /**
     * 创建独立源码同步任务服务。
     *
     * @param configRepository 模块配置仓储
     * @param syncTool         源码同步端口
     */
    public SourceCodeSyncJobService(LogModuleConfigRepository configRepository,
            SourceCodeSyncTool syncTool) {
        repository = configRepository;
        sourceCodeSync = syncTool;
    }

    /**
     * 串行同步指定环境中启用源码同步的全部模块。
     *
     * @param environment 环境编码
     * @return 本次查询和执行汇总
     */
    public JobResult run(String environment) {
        if (!hasText(environment)) {
            throw new IllegalArgumentException("源码同步环境不能为空");
        }
        String targetEnvironment = environment.trim();
        List<LogModuleConfig> modules = repository
                .findCodeSyncEnabledModules(targetEnvironment);
        int success = 0;
        List<String> failedModules = new ArrayList<String>();
        for (LogModuleConfig module : modules) {
            try {
                sourceCodeSync.syncLatest(module);
                success++;
            } catch (RuntimeException exception) {
                String moduleIdentity = moduleIdentity(module);
                failedModules.add(moduleIdentity);
                log.error("event=scheduled_source_code_sync_module_failed "
                                + "定时源码同步模块失败：environment={}, systemCode={}, moduleCode={}",
                        targetEnvironment, module.getSystemCode(), module.getModuleCode(), exception);
            }
        }
        return new JobResult(modules.size(), success, failedModules);
    }

    /** 使用系统和模块编码形成可读且不包含凭据的失败模块标识。 */
    private static String moduleIdentity(LogModuleConfig module) {
        return module.getSystemCode() + "/" + module.getModuleCode();
    }

    /** 单次独立源码同步任务汇总。 */
    public static final class JobResult {
        private final int totalCount;
        private final int successCount;
        private final List<String> failedModules;

        public JobResult(int total, int success, List<String> failures) {
            totalCount = total;
            successCount = success;
            failedModules = Collections.unmodifiableList(
                    new ArrayList<String>(failures));
        }

        public int getTotalCount() {
            return totalCount;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getFailureCount() {
            return failedModules.size();
        }

        public List<String> getFailedModules() {
            return failedModules;
        }
    }
}
