package io.github.badfisher.ailog.domain.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 按供应商编码和模型编码解析 AI Provider 的只读注册表。 */
public final class AiAnalysisProviderRegistry {

    private final Map<String, AiAnalysisProvider> providers;
    private final String defaultKey;

    public AiAnalysisProviderRegistry(Map<String, AiAnalysisProvider> values,
            String defaultProvider, String defaultModel) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("AI provider registry must not be empty");
        }
        providers = Collections.unmodifiableMap(
                new LinkedHashMap<String, AiAnalysisProvider>(values));
        defaultKey = routeKey(defaultProvider, defaultModel);
        if (!providers.containsKey(defaultKey)) {
            throw new IllegalArgumentException("Default AI provider route is not registered: "
                    + defaultKey);
        }
    }

    public AiAnalysisProvider getDefaultProvider() {
        return providers.get(defaultKey);
    }

    public AiAnalysisProvider get(String providerCode, String modelCode) {
        String routeKey = routeKey(providerCode, modelCode);
        AiAnalysisProvider provider = providers.get(routeKey);
        if (provider == null) {
            throw new IllegalArgumentException("AI provider route is not registered: " + routeKey);
        }
        return provider;
    }

    public int size() {
        return providers.size();
    }

    /**
     * 生成注册表内部使用的确定性路由键。
     *
     * @param providerCode Provider 编码
     * @param modelCode    模型编码
     * @return {@code provider:model} 格式的路由键
     */
    public static String routeKey(String providerCode, String modelCode) {
        if (providerCode == null || providerCode.trim().isEmpty()
                || modelCode == null || modelCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Provider code and model code must not be empty");
        }
        return providerCode.trim() + ":" + modelCode.trim();
    }
}
