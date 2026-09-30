package io.github.badfisher.ailog.domain.ai;

import io.github.badfisher.ailog.domain.issue.ProblemLevel;
import io.github.badfisher.ailog.domain.issue.ProblemType;

import lombok.Getter;

/**
 * 单个 Issue Group 的结构化 AI 分析结果。
 *
 * <p>一个 Group 无论包含多少代表样本都只产生一个结论。原始响应和供应商元数据
 * 仅用于调用审计，确定性分类字段不得由本结果覆盖。</p>
 */
@Getter
public final class AiAnalysisResult {

    private final AiJudgement judgement;
    private final ProblemLevel severity;
    private final String category;
    private final String title;
    private final String summary;
    private final String analysisBasis;
    private final String rootCause;
    private final String impact;
    private final String recommendation;
    private final Integer suggestedResolutionDays;
    private final String verification;
    private final String uncertainty;
    private final double confidence;
    private final boolean humanReviewRequired;
    private final String ruleSuggestion;
    private final String provider;
    private final String model;
    private final String promptVersion;
    private final String rawResponse;
    private final String providerRequestId;
    private final String finishReason;
    private final Integer inputTokens;
    private final Integer outputTokens;
    private final Integer totalTokens;

    /** 构造包含建议解决天数的结构化分析结果。 */
    public AiAnalysisResult(AiJudgement resultJudgement, ProblemLevel resultSeverity,
            String resultCategory, String resultTitle, String resultSummary,
            String resultAnalysisBasis, String resultRootCause, String resultImpact,
            String resultRecommendation, String resultVerification, String resultUncertainty,
            Integer resolutionDays, double resultConfidence, boolean reviewRequired,
            String resultRuleSuggestion, String providerCode, String modelCode,
            String prompt, String response, String requestId, String responseFinishReason,
            Integer tokensInput, Integer tokensOutput, Integer tokensTotal) {
        if (resultJudgement == null || resultSeverity == null) {
            throw new IllegalArgumentException("Judgement and severity must not be null");
        }
        // NaN does not satisfy either range comparison and cannot be persisted as a confidence.
        if (Double.isNaN(resultConfidence) || resultConfidence < 0D || resultConfidence > 1D) {
            throw new IllegalArgumentException("Confidence must be within [0,1]");
        }
        if (resolutionDays != null && resolutionDays.intValue() < 1) {
            throw new IllegalArgumentException("Suggested resolution days must be positive");
        }
        judgement = resultJudgement;
        severity = resultSeverity;
        category = ProblemType.valueOf(resultCategory).name();
        title = resultTitle;
        summary = resultSummary;
        analysisBasis = resultAnalysisBasis;
        rootCause = resultRootCause;
        impact = resultImpact;
        recommendation = resultRecommendation;
        suggestedResolutionDays = resolutionDays;
        verification = resultVerification;
        uncertainty = resultUncertainty;
        confidence = resultConfidence;
        humanReviewRequired = reviewRequired;
        ruleSuggestion = resultRuleSuggestion;
        provider = providerCode;
        model = modelCode;
        promptVersion = prompt;
        rawResponse = response;
        providerRequestId = requestId;
        finishReason = responseFinishReason;
        inputTokens = tokensInput;
        outputTokens = tokensOutput;
        totalTokens = tokensTotal;
    }

}
