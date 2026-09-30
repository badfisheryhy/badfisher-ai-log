package io.github.badfisher.ailog.domain.ai;

import lombok.Getter;

import java.math.BigDecimal;

/** AI 批次报告中的单个 Item 结构化结果。 */
@Getter
public final class AiTaskDeliveryItem {

    private final long issueGroupId;
    private final long occurrenceCount;
    private final String status;
    private final String judgement;
    private final String severity;
    private final String aiCategory;
    private final String title;
    private final String summary;
    private final String analysisBasis;
    private final String rootCause;
    private final String impact;
    private final String recommendation;
    private final Integer suggestedResolutionDays;
    private final String verification;
    private final String uncertainty;
    private final String ruleSuggestion;
    private final BigDecimal confidence;
    private final boolean humanReviewRequired;
    private final String errorCode;
    private final String errorMessage;

    public AiTaskDeliveryItem(long groupId, long occurrences, String itemStatus,
            String itemJudgement, String itemSeverity, String category, String itemTitle,
            String itemSummary, String basis, String cause, String itemImpact,
            String action, Integer resolutionDays, String check, String uncertain,
            String itemRuleSuggestion, BigDecimal score, boolean reviewRequired,
            String itemErrorCode, String itemErrorMessage) {
        issueGroupId = groupId;
        occurrenceCount = occurrences;
        status = itemStatus;
        judgement = itemJudgement;
        severity = itemSeverity;
        aiCategory = category;
        title = itemTitle;
        summary = itemSummary;
        analysisBasis = basis;
        rootCause = cause;
        impact = itemImpact;
        recommendation = action;
        suggestedResolutionDays = resolutionDays;
        verification = check;
        uncertainty = uncertain;
        ruleSuggestion = itemRuleSuggestion;
        confidence = score;
        humanReviewRequired = reviewRequired;
        errorCode = itemErrorCode;
        errorMessage = itemErrorMessage;
    }

}
