package io.github.badfisher.ailog.bootstrap.integration.ai;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.badfisher.ailog.bootstrap.integration.git.GitWorkspacePathResolver;
import io.github.badfisher.ailog.domain.ai.AiAnalysisContract;
import io.github.badfisher.ailog.domain.ai.AiAnalysisProvider;
import io.github.badfisher.ailog.domain.ai.AiAnalysisRequest;
import io.github.badfisher.ailog.domain.ai.AiAnalysisResult;
import io.github.badfisher.ailog.domain.ai.AiJudgement;
import io.github.badfisher.ailog.domain.ai.AiProviderException;
import io.github.badfisher.ailog.domain.ai.AiProviderException.ErrorType;
import io.github.badfisher.ailog.domain.issue.ProblemLevel;
import io.github.badfisher.ailog.domain.issue.ProblemType;
import io.github.badfisher.ailog.parser.security.SensitiveLogSanitizer;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/**
 * 使用 Responses API Function Calling 对单个 Issue 执行受限源码取证。
 *
 * <p>一次持久化 Attempt 可以包含多轮“模型选择工具 -> 本地只读执行 -> 返回结果”，
 * 但不包含隐藏重试。任一 HTTP 调用失败时整个 Attempt 失败，由现有任务状态机决定重试。
 * 每个请求根据证据中的环境、系统和模块创建独立源码执行器，禁止通过全局可变目录切换
 * 仓库。每轮 Token 会累加，源码内容不会写入 Attempt，只保留工具路线和最终精简 JSON。</p>
 * <p>源码工具额度耗尽后追加一次禁止工具调用的收尾请求，基于已有证据输出结论。
 * 收尾仍属于当前 Attempt，沿用结果校验和失败处理，不保证证据不足时能够确定根因。</p>
 */
public final class OpenAiFunctionCallingAnalysisProvider implements AiAnalysisProvider {

    private static final SensitiveLogSanitizer SENSITIVE_LOG_SANITIZER =
            new SensitiveLogSanitizer();

    private static final Set<String> ACTUAL_PROBLEM_VALUES = new HashSet<String>(Arrays.asList(
            "YES", "NO", "UNKNOWN"));
    private static final Set<String> NEED_CHANGE_VALUES = new HashSet<String>(Arrays.asList(
            "YES", "NO", "PARTIAL", "UNKNOWN"));

    private final OpenAiResponsesClient responsesClient;
    private final ObjectMapper objectMapper;
    private final FunctionCallingAnalysisProperties properties;
    private final GitWorkspacePathResolver workspacePathResolver;
    private final String providerCode;
    private final String modelCode;

    public OpenAiFunctionCallingAnalysisProvider(
            OpenAiResponsesClient client, ObjectMapper mapper,
            FunctionCallingAnalysisProperties configuredProperties,
            GitWorkspacePathResolver configuredWorkspacePathResolver,
            String configuredProvider, String configuredModel) {
        if (client == null || mapper == null || configuredProperties == null
                || configuredWorkspacePathResolver == null) {
            throw new IllegalArgumentException("Function Calling provider dependencies are required");
        }
        responsesClient = client;
        objectMapper = mapper;
        properties = configuredProperties;
        workspacePathResolver = configuredWorkspacePathResolver;
        providerCode = requiredConfiguration(configuredProvider, "AI provider code");
        modelCode = requiredConfiguration(configuredModel, "AI model code");
        requireRange(configuredProperties.getMaxToolCalls(), 1, 12, "maxToolCalls");
    }

    @Override
    public AiAnalysisResult analyze(AiAnalysisRequest request) {
        if (request == null || request.getEvidence() == null) {
            throw new AiProviderException(ErrorType.REQUEST_ERROR,
                    "AI analysis request must contain sanitized evidence");
        }
        if (!AiAnalysisContract.FUNCTION_CALLING_PROMPT_VERSION.equals(
                request.getPromptVersion())) {
            throw new AiProviderException(ErrorType.REQUEST_ERROR,
                    "Unsupported Function Calling prompt version: "
                            + request.getPromptVersion());
        }
        SourceCodeToolExecutor sourceTools = createSourceTools(request);
        ArrayNode input = objectMapper.createArrayNode();
        input.add(initialInput(request));
        List<String> toolRoute = new ArrayList<String>();
        TokenUsage totalUsage = new TokenUsage();
        int responseCount = 0;

        while (responseCount <= properties.getMaxToolCalls()) {
            boolean finalizing = toolRoute.size() >= properties.getMaxToolCalls();
            responseCount++;
            JsonNode response;
            try {
                response = callResponses(input, sourceTools, finalizing);
            } catch (AiProviderException ex) {
                // 失败响应沿用客户端已脱敏的元数据，只累加已知用量，不把缺失计数补成零。
                throw new AiProviderException(ex.getErrorType(), ex.getMessage(), null,
                        ex.getProviderRequestId(), ex.getActualModel(), ex.getRawResponse(),
                        ex.getFinishReason(),
                        TokenUsage.add(totalUsage.inputTokens, ex.getInputTokens()),
                        TokenUsage.add(totalUsage.outputTokens, ex.getOutputTokens()),
                        TokenUsage.add(totalUsage.totalTokens, ex.getTotalTokens()));
            }
            totalUsage.add(response.path("usage"));
            validateResponseState(response, totalUsage);
            List<JsonNode> functionCalls = functionCalls(response);
            if (functionCalls.isEmpty()) {
                String outputText = outputText(response);
                JsonNode result = parseResult(outputText, response, totalUsage);
                String audit = auditJson(response, result, toolRoute, responseCount);
                try {
                    return toAnalysisResult(result, audit, text(response, "id", null),
                            text(response, "model", modelCode),
                            text(response, "status", "completed"),
                            totalUsage.inputTokens, totalUsage.outputTokens,
                            totalUsage.totalTokens, toolRoute);
                } catch (AiProviderException ex) {
                    // 结果校验失败也必须携带全量审计元数据，多轮工具调用的 token 消耗才能落 Attempt。
                    throw new AiProviderException(ex.getErrorType(), ex.getMessage(), ex,
                            text(response, "id", null), text(response, "model", modelCode),
                            responseMetadata(response), text(response, "status", null),
                            totalUsage.inputTokens, totalUsage.outputTokens,
                            totalUsage.totalTokens);
                }
            }

            if (finalizing) {
                throw invalidResponse("AI requested source tools during finalization", response,
                        totalUsage);
            }
            appendResponseOutput(input, response.path("output"));
            for (JsonNode functionCall : functionCalls) {
                String name = requiredResponseText(functionCall, "name");
                String callId = requiredResponseText(functionCall, "call_id");
                String arguments = requiredResponseText(functionCall, "arguments");
                String output;
                if (toolRoute.size() < properties.getMaxToolCalls()) {
                    output = sourceTools.execute(name, arguments);
                    toolRoute.add(name);
                } else {
                    // 同一响应可能包含多个调用；未执行的调用也必须返回对应结果，保证协议完整。
                    output = "{\"error\":\"SOURCE_TOOL_BUDGET_EXHAUSTED\","
                            + "\"message\":\"Tool not executed. Finalize using existing evidence.\"}";
                }
                ObjectNode toolOutput = objectMapper.createObjectNode();
                toolOutput.put("type", "function_call_output");
                toolOutput.put("call_id", callId);
                toolOutput.put("output", output);
                input.add(toolOutput);
            }
        }
        throw new AiProviderException(ErrorType.INVALID_RESPONSE,
                "AI source analysis did not finish within the response limit");
    }

    private SourceCodeToolExecutor createSourceTools(AiAnalysisRequest request) {
        String environment = request.getEvidence().getEvidence().getEnvironment();
        String systemCode = request.getEvidence().getEvidence().getSystemCode();
        String moduleCode = request.getEvidence().getEvidence().getModuleCode();
        try {
            Path repository = workspacePathResolver.resolveExistingRepository(
                    environment, systemCode, moduleCode);
            return new SourceCodeToolExecutor(repository, properties, objectMapper);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new AiProviderException(ErrorType.REQUEST_ERROR,
                    "Unable to resolve source repository for AI evidence");
        }
    }

    private JsonNode callResponses(ArrayNode input, SourceCodeToolExecutor sourceTools,
            boolean finalizing) {
        ObjectNode body = objectMapper.createObjectNode();
        String instructions = AiAnalysisContract.FUNCTION_CALLING_SYSTEM_INSTRUCTION
                + "\n输出内容要求：conclusion 直接回答是否存在实际问题，控制在1到2句话，"
                + "不要重复证据或写背景；rootCause 只写最核心的代码、配置或调用行为，"
                + "不要重复结论；changes 最多给3个可执行的关键动作，避免泛泛排查。"
                + "evidence 保留完整证据，但不要复制到 conclusion。";
        if (finalizing) {
            instructions += "\n源码工具调用额度已耗尽，不得继续调用工具。请仅根据已有日志和源码证据，"
                    + "立即输出符合结果 JSON Schema 的最终结论。将尚未确认的信息列入 missing，"
                    + "无法确定的判断使用 UNKNOWN，不得编造根因、源码或执行结果。";
        }
        body.put("instructions", instructions);
        body.set("input", input);
        body.set("tools", sourceTools.toolDefinitions());
        body.put("tool_choice", finalizing ? "none" : "auto");
        body.put("parallel_tool_calls", false);
        body.put("store", false);
        ArrayNode include = body.putArray("include");
        include.add("reasoning.encrypted_content");
        ObjectNode text = body.putObject("text");
        ObjectNode format = text.putObject("format");
        format.put("type", "json_schema");
        format.put("name", "java_log_analysis_result");
        format.put("strict", true);
        format.set("schema", resultSchema());
        return responsesClient.createResponse(body, modelCode);
    }

    private ObjectNode initialInput(AiAnalysisRequest request) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schemaVersion", AiAnalysisContract.REQUEST_SCHEMA_VERSION);
        envelope.put("promptVersion", request.getPromptVersion());
        JsonNode evidence = objectMapper.valueToTree(request.getEvidence().getEvidence());
        envelope.set("evidence", evidence);

        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "message");
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        ObjectNode inputText = content.addObject();
        inputText.put("type", "input_text");
        inputText.put("text", writeJson(envelope));
        return message;
    }

    private void validateResponseState(JsonNode response, TokenUsage usage) {
        if (response == null || !response.isObject()) {
            throw invalidResponse("Responses API returned an empty response", response,
                    usage);
        }
        String status = text(response, "status", null);
        if (!"completed".equals(status)) {
            throw invalidResponse("Responses API did not complete, status=" + status,
                    response, usage);
        }
    }

    private List<JsonNode> functionCalls(JsonNode response) {
        JsonNode output = response.path("output");
        if (!output.isArray()) {
            return Collections.emptyList();
        }
        List<JsonNode> calls = new ArrayList<JsonNode>();
        for (JsonNode item : output) {
            if ("function_call".equals(text(item, "type", null))) {
                calls.add(item);
            }
        }
        return calls;
    }

    private void appendResponseOutput(ArrayNode input, JsonNode output) {
        if (!output.isArray()) {
            throw new AiProviderException(ErrorType.INVALID_RESPONSE,
                    "Responses API function call response has no output array");
        }
        for (JsonNode item : output) {
            input.add(item.deepCopy());
        }
    }

    private String outputText(JsonNode response) {
        JsonNode directOutput = response.get("output_text");
        if (directOutput != null && directOutput.isTextual()
                && hasText(directOutput.asText())) {
            return directOutput.asText();
        }
        StringBuilder result = new StringBuilder();
        JsonNode output = response.path("output");
        if (!output.isArray()) {
            return null;
        }
        for (JsonNode item : output) {
            JsonNode content = item.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                if (!"output_text".equals(text(part, "type", null))) {
                    continue;
                }
                String value = text(part, "text", null);
                if (hasText(value)) {
                    result.append(value);
                }
            }
        }
        return result.length() == 0 ? null : result.toString();
    }

    private JsonNode parseResult(String outputText, JsonNode response, TokenUsage usage) {
        if (!hasText(outputText)) {
            throw invalidResponse("Responses API returned no final output text", response,
                    usage);
        }
        try {
            JsonNode result = objectMapper.readTree(outputText);
            if (result == null || !result.isObject()) {
                throw invalidResponse("Final analysis is not a JSON object", response,
                        usage);
            }
            return result;
        } catch (JsonProcessingException ex) {
            throw invalidResponse("Final analysis is not valid JSON", response, usage);
        }
    }

    private String auditJson(JsonNode response, JsonNode result, List<String> toolRoute,
            int responseCount) {
        ObjectNode audit = objectMapper.createObjectNode();
        audit.put("responseId", text(response, "id", null));
        audit.put("model", text(response, "model", modelCode));
        audit.put("status", text(response, "status", null));
        audit.put("responseCount", responseCount);
        ArrayNode route = audit.putArray("toolRoute");
        for (String toolName : toolRoute) {
            route.add(toolName);
        }
        audit.set("result", result.deepCopy());
        return writeJson(audit);
    }

    AiAnalysisResult toAnalysisResult(JsonNode result, String audit, String requestId,
            String actualModel, String finishReason, Integer inputTokens,
            Integer outputTokens, Integer totalTokens, List<String> toolRoute) {
        String title = requiredText(result, "title", AiAnalysisContract.TITLE_MAX_LENGTH);
        String actualProblem = requiredEnum(result, "actualProblem", ACTUAL_PROBLEM_VALUES);
        String needChange = requiredEnum(result, "needChange", NEED_CHANGE_VALUES);
        String changeScope = requiredText(result, "changeScope",
                AiAnalysisContract.RECOMMENDATION_MAX_LENGTH);
        String category = requiredEnum(result, "category", ProblemType.codes());
        ProblemLevel severity = parseSeverity(requiredText(result, "severity", 16));
        String conclusion = requiredText(result, "conclusion",
                AiAnalysisContract.SUMMARY_MAX_LENGTH);
        String directCause = textAllowEmpty(result, "directCause",
                AiAnalysisContract.ROOT_CAUSE_MAX_LENGTH);
        String rootCause = textAllowEmpty(result, "rootCause",
                AiAnalysisContract.ROOT_CAUSE_MAX_LENGTH);
        String impact = requiredText(result, "impact", AiAnalysisContract.IMPACT_MAX_LENGTH);
        List<String> changes = stringArray(result, "changes", 8,
                AiAnalysisContract.RECOMMENDATION_MAX_LENGTH);
        List<String> doNotChange = stringArray(result, "doNotChange", 8,
                AiAnalysisContract.RULE_SUGGESTION_MAX_LENGTH);
        List<String> evidence = stringArray(result, "evidence", 8,
                AiAnalysisContract.ANALYSIS_BASIS_MAX_LENGTH);
        List<String> missing = stringArray(result, "missing", 8,
                AiAnalysisContract.UNCERTAINTY_MAX_LENGTH);
        List<String> verification = stringArray(result, "verify", 8,
                AiAnalysisContract.VERIFICATION_MAX_LENGTH);
        int resolutionDays = AiResolutionDaysParser.parse(result.get("suggestedResolutionDays"),
                "result has invalid suggestedResolutionDays");

        JsonNode confidence = result.path("confidence");
        if (!confidence.isObject()) {
            throw invalidResult("result has invalid confidence");
        }
        double verdictConfidence = confidenceValue(confidence, "verdict");
        double directCauseConfidence = confidenceValue(confidence, "directCause");
        double rootCauseConfidence = confidenceValue(confidence, "rootCause");
        AiJudgement judgement = judgement(actualProblem, directCause, rootCause,
                verdictConfidence, rootCauseConfidence);
        boolean humanReviewRequired = judgement != AiJudgement.CONFIRMED
                || verdictConfidence < 0.8D || !missing.isEmpty();

        String summary = "实际问题：" + yesNoUnknown(actualProblem)
                + "；是否修改：" + changeDecision(needChange)
                + "；结论：" + conclusion;
        String analysisBasis = routeEvidence(toolRoute, evidence);
        String resolvedRootCause = rootCauseText(directCause, rootCause,
                directCauseConfidence, rootCauseConfidence);
        String recommendation = "修改范围：" + changeScope + "；处理建议："
                + joinedOrDefault(changes, "当前无可执行修改建议");
        String verificationText = joinedOrDefault(verification, "当前未提供验证步骤");
        String uncertainty = missing.isEmpty() ? null : join(missing);
        String ruleSuggestion = doNotChange.isEmpty()
                ? null : "不要修改：" + join(doNotChange);
        return new AiAnalysisResult(judgement, severity, category, title, summary,
                analysisBasis, resolvedRootCause, impact, recommendation,
                verificationText, uncertainty, Integer.valueOf(resolutionDays),
                verdictConfidence, humanReviewRequired,
                ruleSuggestion, providerCode, actualModel,
                AiAnalysisContract.FUNCTION_CALLING_PROMPT_VERSION, audit, requestId,
                finishReason, inputTokens, outputTokens, totalTokens);
    }

    private AiJudgement judgement(String actualProblem, String directCause, String rootCause,
            double verdictConfidence, double rootCauseConfidence) {
        if ("UNKNOWN".equals(actualProblem) || verdictConfidence < 0.5D
                || (!hasText(directCause) && !hasText(rootCause))) {
            return AiJudgement.PENDING_CONFIRMATION;
        }
        if (hasText(rootCause) && rootCauseConfidence >= 0.75D) {
            return AiJudgement.CONFIRMED;
        }
        return AiJudgement.PENDING_CONFIRMATION;
    }

    private String routeEvidence(List<String> toolRoute, List<String> evidence) {
        String route = toolRoute == null || toolRoute.isEmpty()
                ? "未调用源码工具" : join(toolRoute);
        return "工具路线：" + route + "；支撑证据："
                + joinedOrDefault(evidence, "当前无直接证据");
    }

    private static String rootCauseText(String directCause, String rootCause,
            double directCauseConfidence, double rootCauseConfidence) {
        String direct = hasText(directCause) ? directCause : "当前证据不足";
        String root = hasText(rootCause) ? rootCause : "当前证据不足";
        return "直接原因：" + direct + "（置信度 " + decimal(directCauseConfidence)
                + "）；根因：" + root + "（置信度 " + decimal(rootCauseConfidence) + "）";
    }

    private static String yesNoUnknown(String value) {
        if ("YES".equals(value)) {
            return "是";
        }
        if ("NO".equals(value)) {
            return "否";
        }
        return "未知";
    }

    private static String changeDecision(String value) {
        if ("YES".equals(value)) {
            return "是";
        }
        if ("NO".equals(value)) {
            return "否";
        }
        if ("PARTIAL".equals(value)) {
            return "部分";
        }
        return "未知";
    }

    private ProblemLevel parseSeverity(String value) {
        try {
            return ProblemLevel.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw invalidResult("result has invalid severity");
        }
    }

    private String requiredEnum(JsonNode parent, String field, Set<String> allowedValues) {
        String value = requiredText(parent, field, 32);
        if (!allowedValues.contains(value)) {
            throw invalidResult("result has invalid " + field);
        }
        return value;
    }

    private String requiredText(JsonNode parent, String field, int maxLength) {
        String value = textAllowEmpty(parent, field, maxLength);
        if (!hasText(value)) {
            throw invalidResult("result has empty " + field);
        }
        return value;
    }

    private String textAllowEmpty(JsonNode parent, String field, int maxLength) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual()) {
            throw invalidResult("result has invalid " + field);
        }
        String value = node.asText();
        if (value.length() > maxLength) {
            throw invalidResult("result " + field + " exceeds " + maxLength
                    + " characters");
        }
        return value;
    }

    private List<String> stringArray(JsonNode parent, String field, int maxItems,
            int maxItemLength) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray() || node.size() > maxItems) {
            throw invalidResult("result has invalid " + field);
        }
        List<String> values = new ArrayList<String>();
        for (JsonNode item : node) {
            if (!item.isTextual() || !hasText(item.asText())
                    || item.asText().length() > maxItemLength) {
                throw invalidResult("result has invalid item in " + field);
            }
            values.add(item.asText());
        }
        return values;
    }

    private double confidenceValue(JsonNode confidence, String field) {
        JsonNode node = confidence.get(field);
        if (node == null || !node.isNumber()) {
            throw invalidResult("result has invalid confidence." + field);
        }
        double value = node.asDouble(-1D);
        if (value < 0D || value > 1D) {
            throw invalidResult("result confidence." + field + " is outside [0,1]");
        }
        return value;
    }

    private ObjectNode resultSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode fields = schema.putObject("properties");
        addString(fields, "title", 1, AiAnalysisContract.TITLE_MAX_LENGTH);
        addEnum(fields, "actualProblem", ACTUAL_PROBLEM_VALUES);
        addEnum(fields, "needChange", NEED_CHANGE_VALUES);
        addString(fields, "changeScope", 1, AiAnalysisContract.RECOMMENDATION_MAX_LENGTH);
        addEnum(fields, "category", ProblemType.codes());
        addEnum(fields, "severity", ProblemLevel.codes());
        addString(fields, "conclusion", 1, AiAnalysisContract.SUMMARY_MAX_LENGTH);
        addString(fields, "directCause", 0, AiAnalysisContract.ROOT_CAUSE_MAX_LENGTH);
        addString(fields, "rootCause", 0, AiAnalysisContract.ROOT_CAUSE_MAX_LENGTH);
        addString(fields, "impact", 1, AiAnalysisContract.IMPACT_MAX_LENGTH);
        addStringArray(fields, "changes", AiAnalysisContract.RECOMMENDATION_MAX_LENGTH);
        addStringArray(fields, "doNotChange", AiAnalysisContract.RULE_SUGGESTION_MAX_LENGTH);
        addStringArray(fields, "evidence", AiAnalysisContract.ANALYSIS_BASIS_MAX_LENGTH);
        addStringArray(fields, "missing", AiAnalysisContract.UNCERTAINTY_MAX_LENGTH);
        addStringArray(fields, "verify", AiAnalysisContract.VERIFICATION_MAX_LENGTH);
        ObjectNode resolutionDays = fields.putObject("suggestedResolutionDays");
        resolutionDays.put("type", "integer");
        resolutionDays.put("minimum", 1);
        resolutionDays.put("maximum", Integer.MAX_VALUE);
        addConfidenceSchema(fields);
        addRequired(schema, "title", "actualProblem", "needChange", "changeScope",
                "category", "severity", "conclusion", "directCause", "rootCause",
                "impact", "changes", "doNotChange", "evidence", "missing", "verify",
                "suggestedResolutionDays", "confidence");
        return schema;
    }

    private void addConfidenceSchema(ObjectNode fields) {
        ObjectNode confidence = fields.putObject("confidence");
        confidence.put("type", "object");
        confidence.put("additionalProperties", false);
        ObjectNode propertiesNode = confidence.putObject("properties");
        addNumber(propertiesNode, "verdict");
        addNumber(propertiesNode, "directCause");
        addNumber(propertiesNode, "rootCause");
        addRequired(confidence, "verdict", "directCause", "rootCause");
    }

    private void addString(ObjectNode fields, String name, int minLength, int maxLength) {
        ObjectNode field = fields.putObject(name);
        field.put("type", "string");
        field.put("minLength", minLength);
        field.put("maxLength", maxLength);
    }

    private void addEnum(ObjectNode fields, String name, Set<String> values) {
        ObjectNode field = fields.putObject(name);
        field.put("type", "string");
        ArrayNode allowed = field.putArray("enum");
        for (String value : values) {
            allowed.add(value);
        }
    }

    private void addStringArray(ObjectNode fields, String name, int maxItemLength) {
        ObjectNode field = fields.putObject(name);
        field.put("type", "array");
        field.put("maxItems", 8);
        ObjectNode items = field.putObject("items");
        items.put("type", "string");
        items.put("minLength", 1);
        items.put("maxLength", maxItemLength);
    }

    private void addNumber(ObjectNode fields, String name) {
        ObjectNode field = fields.putObject(name);
        field.put("type", "number");
        field.put("minimum", 0D);
        field.put("maximum", 1D);
    }

    private void addRequired(ObjectNode parent, String... fields) {
        ArrayNode required = parent.putArray("required");
        for (String field : fields) {
            required.add(field);
        }
    }

    private String requiredResponseText(JsonNode parent, String field) {
        String value = text(parent, field, null);
        if (!hasText(value)) {
            throw new AiProviderException(ErrorType.INVALID_RESPONSE,
                    "Responses API function call has empty " + field);
        }
        return value;
    }

    private AiProviderException invalidResponse(String message, JsonNode response,
            TokenUsage usage) {
        String responseId = text(response, "id", null);
        String actualModel = text(response, "model", modelCode);
        String status = text(response, "status", null);
        String rawResponse = responseMetadata(response);
        // 原始 JSON 异常可能携带生成内容，避免经异常链绕过审计脱敏。
        return new AiProviderException(ErrorType.INVALID_RESPONSE,
                SENSITIVE_LOG_SANITIZER.sanitizeForExternal(message), null,
                responseId, actualModel, rawResponse, status,
                usage == null ? null : usage.inputTokens,
                usage == null ? null : usage.outputTokens,
                usage == null ? null : usage.totalTokens);
    }

    private AiProviderException invalidResult(String message) {
        return new AiProviderException(ErrorType.INVALID_RESPONSE, message);
    }

    private String responseMetadata(JsonNode response) {
        if (response == null || !response.isObject()) {
            return null;
        }
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("id", text(response, "id", null));
        metadata.put("model", text(response, "model", null));
        metadata.put("status", text(response, "status", null));
        if (response.has("error")) {
            metadata.set("error", response.get("error").deepCopy());
        }
        return SENSITIVE_LOG_SANITIZER.sanitizeForExternal(writeJson(metadata));
    }

    private String writeJson(Object value) {
        return AiJsonSerialization.write(objectMapper, value,
                "Unable to serialize Function Calling data");
    }

    private static String text(JsonNode parent, String field, String defaultValue) {
        if (parent == null) {
            return defaultValue;
        }
        JsonNode node = parent.get(field);
        return node != null && node.isTextual() ? node.asText() : defaultValue;
    }

    private static String joinedOrDefault(List<String> values, String defaultValue) {
        return values == null || values.isEmpty() ? defaultValue : join(values);
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append("；");
            }
            result.append(value);
        }
        return result.toString();
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String requiredConfiguration(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return value.trim();
    }

    private static void requireRange(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be within [" + minimum
                    + "," + maximum + "]");
        }
    }

    private static final class TokenUsage {

        private Integer inputTokens;
        private Integer outputTokens;
        private Integer totalTokens;

        private void add(JsonNode usage) {
            if (usage == null || !usage.isObject()) {
                return;
            }
            inputTokens = add(inputTokens, integer(usage.get("input_tokens")));
            outputTokens = add(outputTokens, integer(usage.get("output_tokens")));
            Integer responseTotal = integer(usage.get("total_tokens"));
            if (responseTotal == null) {
                Integer responseInput = integer(usage.get("input_tokens"));
                Integer responseOutput = integer(usage.get("output_tokens"));
                responseTotal = responseInput == null || responseOutput == null
                        ? null : Integer.valueOf(responseInput.intValue()
                                + responseOutput.intValue());
            }
            totalTokens = add(totalTokens, responseTotal);
        }

        private static Integer integer(JsonNode node) {
            return node != null && node.canConvertToInt()
                    ? Integer.valueOf(node.intValue()) : null;
        }

        private static Integer add(Integer current, Integer addition) {
            if (addition == null) {
                return current;
            }
            return Integer.valueOf((current == null ? 0 : current.intValue())
                    + addition.intValue());
        }
    }
}
