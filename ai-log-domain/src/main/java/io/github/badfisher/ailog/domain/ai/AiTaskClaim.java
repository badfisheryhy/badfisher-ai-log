package io.github.badfisher.ailog.domain.ai;

import lombok.Getter;

/** 已通过条件更新取得租约的 AI Item。 */
@Getter
public final class AiTaskClaim {
    private final long itemId;
    private final long aiTaskId;
    private final long issueGroupId;
    private final String providerCode;
    private final String modelCode;
    private final String promptVersion;
    private final String sanitizerVersion;
    private final int attemptCount;
    private final int maxAttempts;
    private final String claimToken;

    public AiTaskClaim(long claimedItemId, long taskId, long groupId,
            String provider, String model, String prompt, String sanitizer,
            int attempts, int maximumAttempts, String token) {
        itemId = claimedItemId;
        aiTaskId = taskId;
        issueGroupId = groupId;
        providerCode = provider;
        modelCode = model;
        promptVersion = prompt;
        sanitizerVersion = sanitizer;
        attemptCount = attempts;
        maxAttempts = maximumAttempts;
        claimToken = token;
    }

}
