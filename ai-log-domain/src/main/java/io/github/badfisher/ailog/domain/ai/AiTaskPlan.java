package io.github.badfisher.ailog.domain.ai;

import lombok.Getter;

/** 解析完成时固化的 AI 任务配置。 */
@Getter
public final class AiTaskPlan {

    public static final int HARD_SELECTION_LIMIT = 200;
    /** 单次数据库分页的最大 Item 数，不是整个任务的 Group 上限。 */
    public static final int HARD_DISPATCH_LIMIT = 50;
    public static final int HARD_SAMPLE_LIMIT = 3;
    public static final int HARD_MAX_ATTEMPTS = 3;

    private final boolean enabled;
    private final String providerCode;
    private final String modelProfileCode;
    private final String modelCode;
    private final String promptVersion;
    private final String sanitizerVersion;
    private final String modelParametersJson;
    private final int selectionLimit;
    private final int maxAttempts;
    private final long retryBackoffMillis;

    public AiTaskPlan(boolean taskEnabled, String provider, String modelProfile, String model,
            String prompt, String sanitizer, String parametersJson, int maxSelectedItems,
            int attempts, long retryBackoff) {
        enabled = taskEnabled;
        providerCode = requireText(provider, "Provider code");
        modelProfileCode = requireText(modelProfile, "Model profile code");
        modelCode = requireText(model, "Model code");
        promptVersion = requireText(prompt, "Prompt version");
        sanitizerVersion = requireText(sanitizer, "Sanitizer version");
        modelParametersJson = requireText(parametersJson, "Model parameters JSON");
        if (maxSelectedItems < 1 || maxSelectedItems > HARD_SELECTION_LIMIT) {
            throw new IllegalArgumentException("AI selection limit must be within [1,200]");
        }
        if (attempts < 1 || attempts > HARD_MAX_ATTEMPTS) {
            throw new IllegalArgumentException("AI max attempts must be within [1,3]");
        }
        if (retryBackoff < 0L) {
            throw new IllegalArgumentException("AI retry backoff must not be negative");
        }
        selectionLimit = maxSelectedItems;
        maxAttempts = attempts;
        retryBackoffMillis = retryBackoff;
    }

    public static AiTaskPlan disabled() {
        return new AiTaskPlan(false, "disabled", "disabled", "disabled",
                AiAnalysisContract.PROMPT_VERSION, "disabled", "{}", 1, 1, 0L);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

}
