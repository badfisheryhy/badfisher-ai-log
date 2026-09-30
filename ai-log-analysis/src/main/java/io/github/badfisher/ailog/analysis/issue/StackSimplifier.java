package io.github.badfisher.ailog.analysis.issue;

import io.github.badfisher.ailog.domain.issue.ExceptionStructure;

/** 将完整异常结构压缩为适合持久化和模型输入的稳定摘要。 */
public final class StackSimplifier {

    /** 栈摘要最小长度限制。 */
    private static final int MIN_STACK_LIMIT = 1024;
    /** 栈摘要最大长度限制。 */
    private static final int MAX_STACK_LIMIT = 65536;

    /** 摘要最大字符数。 */
    private final int maxChars;

    /**
     * 构造栈摘要简化器。
     *
     * @param maximumChars 摘要最大字符数
     * @throws IllegalArgumentException 长度限制超出 [1024, 65536] 时抛出
     */
    public StackSimplifier(int maximumChars) {
        if (maximumChars < MIN_STACK_LIMIT || maximumChars > MAX_STACK_LIMIT) {
            throw new IllegalArgumentException("invalid stack limit");
        }
        maxChars = maximumChars;
    }

    /**
     * 将异常结构压缩为稳定摘要。
     *
     * @param structure 异常结构
     * @return 截断到上限的栈摘要
     */
    public String simplify(ExceptionStructure structure) {
        StringBuilder result = new StringBuilder();
        append(result, "logger",
                join(structure.getLoggerClass(), structure.getLoggerMethod(), structure.getLoggerLine()));
        append(result, "exception",
                join(structure.getExceptionClass(), structure.getExceptionMessage(), null));
        append(result, "rootCause",
                join(structure.getRootCauseException(), structure.getRootCauseMessage(), null));
        for (String frame : structure.getBusinessFrames()) {
            append(result, "at", frame);
        }
        return result.length() <= maxChars ? result.toString() : result.substring(0, maxChars);
    }

    /**
     * 拼接 类型.值:行号 形态的字段。
     *
     * @param type  类型名
     * @param value 值
     * @param line  行号，可为空
     * @return 拼接结果，类型和值都为空时返回 null
     */
    private static String join(String type, String value, Integer line) {
        if (type == null && value == null) {
            return null;
        }
        return String.valueOf(type) + (value == null ? "" : "." + value)
                + (line == null ? "" : ":" + line);
    }

    /**
     * 向摘要追加一行 标签=值。
     *
     * @param target 摘要构建器
     * @param label  标签
     * @param value  值，为空时跳过
     */
    private static void append(StringBuilder target, String label, String value) {
        if (value == null) {
            return;
        }
        if (target.length() > 0) {
            target.append('\n');
        }
        target.append(label).append('=').append(value);
    }
}
