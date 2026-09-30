package io.github.badfisher.ailog.bootstrap.integration.ai;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import io.github.badfisher.ailog.domain.ai.AiAnalysisContract;
import io.github.badfisher.ailog.domain.ai.AiAnalysisProvider;
import io.github.badfisher.ailog.domain.ai.AiAnalysisRequest;
import io.github.badfisher.ailog.domain.ai.AiAnalysisResult;
import io.github.badfisher.ailog.domain.ai.AiJudgement;
import io.github.badfisher.ailog.domain.ai.AiProviderException;
import io.github.badfisher.ailog.domain.ai.AiProviderException.ErrorType;
import io.github.badfisher.ailog.domain.issue.ProblemLevel;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.badfisher.ailog.domain.issue.ProblemType;
import io.github.badfisher.ailog.parser.security.SensitiveLogSanitizer;

/**
 * 使用可配置 Responses API 的单次分析 Provider。
 *
 * <p>每次 {@link #analyze(AiAnalysisRequest)} 只发起一次真实 HTTP 调用。
 * 重试由持久化 Item/Attempt 状态机负责，Provider 内禁止睡眠或隐藏重试。</p>
 */
public class OpenAiAnalysisProvider implements AiAnalysisProvider {

    public static final String SUPPORTED_PROMPT_VERSION = AiAnalysisContract.PROMPT_VERSION;

    private static final String DEFAULT_PROVIDER_CODE = "openai";
    private static final SensitiveLogSanitizer SENSITIVE_LOG_SANITIZER =
            new SensitiveLogSanitizer();
    private static final String MISSING_ROOT_CAUSE = "AI 未给出明确根因，需人工复核";
    private static final double INCOMPLETE_RESULT_MAX_CONFIDENCE = 0.5D;
    private static final Set<String> RESULT_FIELDS = new HashSet<String>(Arrays.asList(
            "judgement", "severity", "category", "title", "summary", "analysisBasis",
            "rootCause", "impact", "recommendation", "uncertainty", "confidence",
            "humanReviewRequired", "ruleSuggestion", "suggestedResolutionDays"));

    private final OpenAiResponsesClient client;
    private final ObjectMapper objectMapper;
    private final String configuredProvider;
    private final String configuredModel;

    public OpenAiAnalysisProvider(OpenAiResponsesClient client, ObjectMapper mapper,
            String provider, String model) {
        this.client = Objects.requireNonNull(client, "Responses client is required");
        objectMapper = Objects.requireNonNull(mapper, "ObjectMapper is required");
        configuredProvider = provider;
        configuredModel = model;
    }

    @Override
    public AiAnalysisResult analyze(AiAnalysisRequest request) {
        validateRequest(request);
        ResponseText response = new ResponseText(client.createResponse(buildRequest(request), configuredModel));
        try {
            return parseResponse(response, request.getPromptVersion());
        } catch (AiProviderException exception) {
            throw new AiProviderException(exception.getErrorType(), exception.getMessage(), null,
                    response.getRequestId(), response.getModel(),
                    SENSITIVE_LOG_SANITIZER.sanitizeForExternal(response.getContent()),
                    response.getFinishReason(), response.getInputTokens(),
                    response.getOutputTokens(), response.getTotalTokens());
        }
    }
    private void validateRequest(AiAnalysisRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Analysis request must not be null");
        }
        if (!SUPPORTED_PROMPT_VERSION.equals(request.getPromptVersion())) {
            throw new IllegalArgumentException("Unsupported prompt version: "
                    + request.getPromptVersion() + ", expected: " + SUPPORTED_PROMPT_VERSION);
        }
    }

    private ObjectNode buildRequest(AiAnalysisRequest request) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", AiAnalysisContract.REQUEST_SCHEMA_VERSION);
        envelope.put("evidence", request.getEvidence().getEvidence());
        ObjectNode body = objectMapper.createObjectNode();
        body.put("instructions", AiAnalysisContract.SYSTEM_INSTRUCTION);
        body.put("input", writeJson(envelope));
        body.putObject("text").putObject("format").put("type", "json_object");
        return body;
    }
    private AiAnalysisResult parseResponse(ResponseText response, String promptVersion) {
        if (response == null || !StringUtils.hasText(response.getContent())) {
            throw invalidResponse("response has empty content");
        }
        JsonNode result = readTree(response.getContent());
        if (!result.isObject()) {
            throw invalidResponse("response content is not a JSON object");
        }
        rejectUnknownFields(result);

        AiJudgement judgement = parseJudgement(result.path("judgement"));
        ProblemLevel severity = parseSeverity(result.path("severity"));
        String category = requiredText(result, "category", 32);
        if (!ProblemType.codes().contains(category)) {
            throw invalidResponse("response has invalid category");
        }
        String title = requiredText(result, "title", AiAnalysisContract.TITLE_MAX_LENGTH);
        String summary = requiredText(result, "summary", AiAnalysisContract.SUMMARY_MAX_LENGTH);
        String basis = requiredText(result, "analysisBasis",
                AiAnalysisContract.ANALYSIS_BASIS_MAX_LENGTH);
        JsonNode rootCauseNode = result.get("rootCause");
        boolean rootCauseMissing = isMissingText(rootCauseNode);
        String rootCause = rootCauseMissing ? MISSING_ROOT_CAUSE
                : requiredText(result, "rootCause", AiAnalysisContract.ROOT_CAUSE_MAX_LENGTH);
        String impact = requiredText(result, "impact", AiAnalysisContract.IMPACT_MAX_LENGTH);
        JsonNode recommendation = result.path("recommendation");
        if (!recommendation.isObject()) {
            throw invalidResponse("response has invalid recommendation");
        }
        String action = requiredText(recommendation, "action",
                AiAnalysisContract.RECOMMENDATION_MAX_LENGTH);
        String verification = requiredText(recommendation, "verification",
                AiAnalysisContract.VERIFICATION_MAX_LENGTH);
        int resolutionDays = AiResolutionDaysParser.parse(result.get("suggestedResolutionDays"),
                providerCode() + " response has invalid suggestedResolutionDays");
        String uncertainty = requiredText(result, "uncertainty",
                AiAnalysisContract.UNCERTAINTY_MAX_LENGTH);
        JsonNode confidenceNode = result.get("confidence");
        if (confidenceNode == null || !confidenceNode.isNumber()) {
            throw invalidResponse("response has invalid confidence");
        }
        double confidence = confidenceNode.asDouble(-1D);
        if (confidence < 0D || confidence > 1D) {
            throw invalidResponse("response has invalid confidence");
        }
        JsonNode reviewNode = result.get("humanReviewRequired");
        if (reviewNode == null || !reviewNode.isBoolean()) {
            throw invalidResponse("response has invalid humanReviewRequired");
        }
        AiJudgement resolvedJudgement = rootCauseMissing
                ? AiJudgement.PENDING_CONFIRMATION : judgement;
        double resolvedConfidence = rootCauseMissing
                ? Math.min(confidence, INCOMPLETE_RESULT_MAX_CONFIDENCE) : confidence;
        boolean humanReviewRequired = reviewNode.asBoolean()
                || resolvedJudgement == AiJudgement.PENDING_CONFIRMATION;
        String ruleSuggestion = nullableText(result.get("ruleSuggestion"),
                AiAnalysisContract.RULE_SUGGESTION_MAX_LENGTH, "ruleSuggestion");

        return new AiAnalysisResult(resolvedJudgement, severity, category, title, summary,
                basis, rootCause, impact, action, verification, uncertainty,
                Integer.valueOf(resolutionDays), resolvedConfidence, humanReviewRequired,
                ruleSuggestion, providerCode(),
                modelCode(response), promptVersion, response.getContent(),
                response.getRequestId(), response.getFinishReason(),
                response.getInputTokens(),
                response.getOutputTokens(),
                response.getTotalTokens());
    }

    private void rejectUnknownFields(JsonNode result) {
        Iterator<String> fields = result.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!RESULT_FIELDS.contains(field)) {
                throw invalidResponse("response contains unknown field");
            }
        }
    }

    private AiJudgement parseJudgement(JsonNode node) {
        String value = node.asText(null);
        if ("CONFIRMED".equals(value) || "ROOT_CAUSE_IDENTIFIED".equals(value)
                || "CONFIRMED_PROBLEM".equals(value)) {
            return AiJudgement.CONFIRMED;
        }
        if ("PENDING_CONFIRMATION".equals(value) || "CAUSE_POSSIBLE".equals(value)
                || "LIKELY_PROBLEM".equals(value)
                || "BUSINESS_EXPECTED_SUSPECT".equals(value)
                || "INSUFFICIENT_EVIDENCE".equals(value)) {
            return AiJudgement.PENDING_CONFIRMATION;
        }
        throw invalidResponse("response has invalid judgement");
    }

    private ProblemLevel parseSeverity(JsonNode node) {
        String value = node.asText(null);
        if (ProblemLevel.codes().contains(value)) {
            return ProblemLevel.valueOf(value);
        }
        throw invalidResponse("response has invalid severity");
    }

    private String requiredText(JsonNode parent, String field, int maxLength) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual() || !StringUtils.hasText(node.asText())) {
            throw invalidResponse("response has empty " + field);
        }
        String value = node.asText();
        if (value.length() > maxLength) {
            throw invalidResponse("response " + field + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    private String nullableText(JsonNode node, int maxLength, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalidResponse("response has invalid " + field);
        }
        String value = node.asText();
        if (value.length() > maxLength) {
            throw invalidResponse("response " + field + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    /** 判断可降级处理的缺失文本；非文本类型仍由严格校验拒绝。 */
    private static boolean isMissingText(JsonNode node) {
        return node == null || node.isNull()
                || (node.isTextual() && !StringUtils.hasText(node.asText()));
    }

    private JsonNode readTree(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException ex) {
            throw new AiProviderException(ErrorType.INVALID_RESPONSE,
                    providerCode() + " response content is not valid JSON");
        }
    }

    private String writeJson(Object value) {
        return AiJsonSerialization.write(objectMapper, value,
                "Unable to serialize sanitized AI evidence");
    }

    private AiProviderException invalidResponse(String message) {
        return new AiProviderException(ErrorType.INVALID_RESPONSE,
                providerCode() + " " + message);
    }

    private String providerCode() {
        return StringUtils.hasText(configuredProvider)
                ? configuredProvider.trim() : DEFAULT_PROVIDER_CODE;
    }

    private String modelCode(ResponseText response) {
        return response != null && StringUtils.hasText(response.getModel())
                ? response.getModel().trim() : configuredModel;
    }
}
