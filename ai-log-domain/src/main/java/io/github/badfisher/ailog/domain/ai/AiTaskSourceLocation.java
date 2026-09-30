package io.github.badfisher.ailog.domain.ai;

import lombok.Getter;

/** 一个 AI Item 代表 Event 中可用于源码归属查询的位置。 */
@Getter
public final class AiTaskSourceLocation {
    private final String environment;
    private final String systemCode;
    private final String moduleCode;
    private final String className;
    private final int lineNumber;

    public AiTaskSourceLocation(String environmentValue, String systemCodeValue,
            String moduleCodeValue, String classNameValue, int lineNumberValue) {
        environment = environmentValue;
        systemCode = systemCodeValue;
        moduleCode = moduleCodeValue;
        className = classNameValue;
        lineNumber = lineNumberValue;
    }
}
