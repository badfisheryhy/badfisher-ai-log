package io.github.badfisher.ailog.analysis.group;

import io.github.badfisher.ailog.domain.sync.LogChannel;

/** 以结构化日志级别为事实来源识别 ERROR，专用 ERROR 文件允许无 Header 回退。 */
public final class ErrorFilter {

    /** ERROR 日志级别。 */
    private static final String ERROR_LEVEL = "ERROR";

    /** 无法从标准 Header 解析日志级别时使用的占位值。 */
    private static final String UNKNOWN_LEVEL = "UNKNOWN";

    /** ERROR 识别结果，用于区分严格命中、专用文件回退和拒绝。 */
    public enum MatchType {
        STRICT_ERROR,
        FALLBACK_ERROR,
        REJECTED
    }

    /**
     * 按结构化级别和文件通道判定单条事件。
     * <p>
     * Header 已解析时只接受明确的 ERROR；非 ERROR 级别即使正文包含 ERROR 也拒绝。
     * Header 无法解析时，仅 ERROR 专用文件允许受控回退。
     *
     * @param level         Header 解析出的日志级别
     * @param sourceChannel 文件所属同步通道
     * @return ERROR 识别结果
     */
    public MatchType classify(String level, LogChannel sourceChannel) {
        if (isStrictError(level)) {
            return MatchType.STRICT_ERROR;
        }
        if (hasParsedLevel(level)) {
            return MatchType.REJECTED;
        }
        return sourceChannel == LogChannel.ERROR
                ? MatchType.FALLBACK_ERROR : MatchType.REJECTED;
    }

    private static boolean isStrictError(String level) {
        return ERROR_LEVEL.equalsIgnoreCase(level);
    }

    private static boolean hasParsedLevel(String level) {
        return level != null && !level.trim().isEmpty()
                && !UNKNOWN_LEVEL.equalsIgnoreCase(level);
    }
}
