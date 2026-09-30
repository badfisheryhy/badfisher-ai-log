package io.github.badfisher.ailog.analysis.module;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 分析模块注册表，启动时拒绝空代码和重复代码。 */
public final class ErrorAnalysisModuleRegistry {

    /** 模块编码到模块实例的映射。 */
    private final Map<String, ProjectErrorAnalysisModule> modules;

    /**
     * 构造注册表并建立编码索引。
     *
     * @param values 所有已注册的分析模块
     * @throws IllegalArgumentException 模块编码为空或重复时抛出
     */
    public ErrorAnalysisModuleRegistry(List<ProjectErrorAnalysisModule> values) {
        Map<String, ProjectErrorAnalysisModule> indexed = new LinkedHashMap<>();
        for (ProjectErrorAnalysisModule module : values) {
            String code = module.moduleCode();
            if (code == null || code.trim().isEmpty()) {
                throw new IllegalArgumentException("module code is required");
            }
            if (indexed.put(code, module) != null) {
                throw new IllegalArgumentException("duplicate module: " + code);
            }
        }
        modules = Collections.unmodifiableMap(indexed);
    }

    /**
     * 按编码查找模块。
     *
     * @param code 模块编码
     * @return 匹配的分析模块
     * @throws IllegalArgumentException 未找到对应模块时抛出
     */
    public ProjectErrorAnalysisModule required(String code) {
        ProjectErrorAnalysisModule module = modules.get(code);
        if (module == null) {
            throw new IllegalArgumentException("未知的错误分析模块：" + code);
        }
        return module;
    }
}
