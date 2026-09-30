package io.github.badfisher.ailog.analysis.issue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.badfisher.ailog.domain.issue.ExceptionStructure;
import io.github.badfisher.ailog.domain.log.LogEvent;

/** 从 ERROR 事件中提取异常链、日志位置和第一条有效业务栈。 */
public final class ExceptionStructureExtractor {

    /** 异常消息最大保留长度。 */
    private static final int MAX_EXCEPTION_MESSAGE_LENGTH = 4096;
    /** 异常声明行匹配，含 Caused by/Suppressed 前缀。 */
    private static final Pattern EXCEPTION = Pattern.compile(
            "(?:Caused by:\\s*|Suppressed:\\s*)?([\\w.$]+(?:Exception|Error))(?::\\s*(.*))?");
    /** 栈帧行匹配，含可选行号。 */
    private static final Pattern FRAME = Pattern.compile(
            "\\s*at\\s+([\\w.$]+)\\.([\\w$<>]+)\\([^:()]+(?::(\\d+))?\\)");
    /** 日志位置匹配，形如 类.方法:行号。 */
    private static final Pattern LOGGER = Pattern.compile(
            "\\b([a-zA-Z_$][\\w.$]+)\\.([\\w$<>]+):(\\d+)\\b");

    /** 业务代码包名前缀列表。 */
    private final List<String> businessPrefixes;
    /** 最多收集的业务栈帧数。 */
    private final int maxFrames;

    /**
     * 构造提取器。
     *
     * @param businessPrefix 业务代码包名前缀
     * @param maximumFrames  最多收集的业务栈帧数
     */
    public ExceptionStructureExtractor(String businessPrefix, int maximumFrames) {
        this(Collections.singletonList(businessPrefix), maximumFrames);
    }

    /**
     * 构造支持多个业务包的提取器，防御性复制前缀列表。
     *
     * @param businessPrefixes 业务包前缀列表，null 视为空列表
     * @param maximumFrames 最多收集的业务栈帧数
     */
    public ExceptionStructureExtractor(List<String> businessPrefixes, int maximumFrames) {
        this.businessPrefixes = businessPrefixes == null
                ? Collections.<String>emptyList() : new ArrayList<>(businessPrefixes);
        this.maxFrames = maximumFrames;
    }

    /**
     * 从 ERROR 事件中提取异常结构。
     *
     * @param event 日志事件
     * @return 提取的异常结构
     */
    public ExceptionStructure extract(LogEvent event) {
        String exceptionClass = null;
        String exceptionMessage = null;
        String rootCauseClass = null;
        String rootCauseMessage = null;
        String businessClass = null;
        String businessMethod = null;
        Integer businessLine = null;
        List<String> businessFrames = new ArrayList<>();

        for (String line : event.getContent().split("\\r?\\n")) {
            Matcher exceptionMatcher = EXCEPTION.matcher(line);
            if (exceptionMatcher.find()) {
                if (exceptionClass == null) {
                    exceptionClass = exceptionMatcher.group(1);
                    exceptionMessage = limit(exceptionMatcher.group(2), MAX_EXCEPTION_MESSAGE_LENGTH);
                }
                if (line.contains("Caused by:")) {
                    rootCauseClass = exceptionMatcher.group(1);
                    rootCauseMessage = limit(
                            exceptionMatcher.group(2), MAX_EXCEPTION_MESSAGE_LENGTH);
                }
            }

            Matcher frameMatcher = FRAME.matcher(line);
            if (isBusinessFrame(frameMatcher, line, businessFrames.size())) {
                businessFrames.add(line.trim());
                if (businessClass == null) {
                    businessClass = frameMatcher.group(1);
                    businessMethod = frameMatcher.group(2);
                    businessLine = toInteger(frameMatcher.group(3));
                }
            }
        }

        if (rootCauseClass == null) {
            rootCauseClass = exceptionClass;
            rootCauseMessage = exceptionMessage;
        }

        String loggerClass = event.getLoggerClass();
        String loggerMethod = event.getLoggerMethod();
        Integer loggerLine = event.getLoggerLine();
        Matcher logger = LOGGER.matcher(event.getContent().split("\\r?\\n", 2)[0]);
        if (loggerClass == null && logger.find()) {
            loggerClass = logger.group(1);
            loggerMethod = logger.group(2);
            loggerLine = Integer.valueOf(logger.group(3));
        }

        return new ExceptionStructure(exceptionClass, exceptionMessage, rootCauseClass,
                rootCauseMessage, loggerClass, loggerMethod, loggerLine, businessClass,
                businessMethod, businessLine, businessFrames);
    }

    /**
     * 判断栈帧是否属于业务代码且数量未超限。
     *
     * @param frameMatcher     已匹配的栈帧匹配器
     * @param line             栈帧原始行
     * @param currentFrameCount 已收集的帧数
     * @return 有效业务帧时为 true
     */
    private boolean isBusinessFrame(Matcher frameMatcher, String line, int currentFrameCount) {
        if (!frameMatcher.find() || currentFrameCount >= maxFrames) {
            return false;
        }
        String className = frameMatcher.group(1);
        if (isProxyFrame(className, line)) {
            return false;
        }
        for (String prefix : businessPrefixes) {
            if (prefix != null && !prefix.trim().isEmpty()
                    && (className.equals(prefix) || className.startsWith(prefix + "."))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 空值安全的整数转换。
     *
     * @param value 原始字符串
     * @return 整数，为空时返回 null
     */
    private static Integer toInteger(String value) {
        return value == null ? null : Integer.valueOf(value);
    }

    /**
     * 截断超长消息。
     *
     * @param value          原始消息
     * @param maximumLength  最大长度
     * @return 截断后的消息
     */
    private static String limit(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength);
    }

    /** 代理和字节码增强帧不能成为第一业务栈。 */
    private static boolean isProxyFrame(String className, String line) {
        String text = (className + " " + line).toLowerCase();
        return text.contains("cglib") || text.contains("fastclass")
                || text.contains("enhancer") || text.contains("methodproxy")
                || text.contains("auxiliary") || text.contains("<generated>")
                || text.contains("skywalking");
    }
}
