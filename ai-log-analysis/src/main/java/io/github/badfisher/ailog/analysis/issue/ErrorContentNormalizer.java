package io.github.badfisher.ailog.analysis.issue;

import io.github.badfisher.ailog.domain.text.Sha256;
import io.github.badfisher.ailog.domain.issue.ExceptionStructure;

/** 归一化错误消息并生成代表样本去重键，不参与 Group 身份。 */
public final class ErrorContentNormalizer {

    /** 旧任务表非空字段的固定兼容值。 */
    public static final String GROUPING_MARKER = "rule-group";

    /**
     * 清理动态标识，同时保留 HTTP 状态、SQLState 和明确错误码。
     */
    public String normalize(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "{empty-message}";
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        normalized = normalized.replaceAll("(?i)HTTP ([0-9]{3})", "HTTP_STATUS_$1");
        normalized = normalized.replaceAll("(?i)SQLState ([0-9]+)", "SQL_STATE_$1");
        normalized = normalized.replaceAll(
                "(?i)(\"(?:status|errorCode)\"\\s*:\\s*)([0-9]+)",
                "$1SEMANTIC_CODE_$2");
        normalized = normalized.replaceAll(
                "(\"[^\"]+\"\\s*:\\s*)-?[0-9]+",
                "$1{id}");
        normalized = normalized.replaceAll("(?i)wx[0-9]{8}[a-z]+", "{id}");
        normalized = normalized.replaceAll("(?<![A-Za-z0-9_])[0-9]+(?![A-Za-z0-9_])", "{id}");
        normalized = normalized.replaceAll("[0-9]{6,}", "{id}");
        normalized = normalized.replace("HTTP_STATUS_", "HTTP ");
        normalized = normalized.replace("SQL_STATE_", "SQLState ");
        normalized = normalized.replace("SEMANTIC_CODE_", "");
        return normalized;
    }

    /** 生成代表样本内容键，只用于判断样本是否变化。 */
    public String sampleKey(ExceptionStructure structure, String message) {
        String source = value(structure == null ? null : structure.getExceptionClass())
                + "|" + value(structure == null ? null : structure.getRootCauseException())
                + "|" + normalize(message);
        return Sha256.sha256(source);
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }
}
