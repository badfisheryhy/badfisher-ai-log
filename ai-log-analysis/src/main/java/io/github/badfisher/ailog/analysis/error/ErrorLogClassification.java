package io.github.badfisher.ailog.analysis.error;

import lombok.Getter;

/**
 * 单条 ERROR 日志的确定性分类结果。
 */
@Getter
public final class ErrorLogClassification {

    /** 业务分类。 */
    private final ErrorCategory category;
    /** 出错来源类名，可能为空。 */
    private final String sourceClass;
    /** 出错来源方法名，可能为空。 */
    private final String sourceMethod;
    /** 异常类名，可能为空。 */
    private final String exceptionClass;

    /**
     * 构造分类结果。
     *
     * @param errorCategory      业务分类
     * @param sourceClassName    出错来源类名
     * @param sourceMethodName   出错来源方法名
     * @param exceptionClassName 异常类名
     */
    public ErrorLogClassification(ErrorCategory errorCategory, String sourceClassName, String sourceMethodName,
            String exceptionClassName) {
        category = errorCategory;
        sourceClass = sourceClassName;
        sourceMethod = sourceMethodName;
        exceptionClass = exceptionClassName;
    }

}
