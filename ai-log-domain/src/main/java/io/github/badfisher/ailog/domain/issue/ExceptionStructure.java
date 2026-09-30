package io.github.badfisher.ailog.domain.issue;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ERROR 的有限结构化表示，承载异常链与首个业务栈信息。 */
@Getter
public final class  ExceptionStructure {
    /** 最外层异常类全限定名；无异常时为 {@code null}。 */
    private final String exceptionClass;

    /** 最外层异常消息；超长时截断。 */
    private final String exceptionMessage;

    /** 根因异常类全限定名；无 {@code Caused by} 时等于最外层异常类。 */
    private final String rootCauseException;

    /** 根因异常消息。 */
    private final String rootCauseMessage;

    /** 日志来源 Logger 类名。 */
    private final String loggerClass;

    /** 日志来源 Logger 方法名。 */
    private final String loggerMethod;

    /** 日志来源行号；未知时为 {@code null}。 */
    private final Integer loggerLine;

    /** 首个业务栈帧类名；无业务帧时为 {@code null}。 */
    private final String businessClass;

    /** 首个业务栈帧方法名。 */
    private final String businessMethod;

    /** 首个业务栈帧行号。 */
    private final Integer businessLine;

    /** 业务栈帧行文本列表，按出现顺序不可变。 */
    private final List<String> businessFrames;

    /**
     * 构造异常结构。
     *
     * @param exceptionClass    最外层异常类
     * @param exceptionMessage  最外层异常消息
     * @param rootCauseException 根因异常类
     * @param rootCauseMessage  根因异常消息
     * @param loggerClass       日志来源类名
     * @param loggerMethod      日志来源方法名
     * @param loggerLine        日志来源行号
     * @param businessClass     首个业务栈帧类名
     * @param businessMethod    首个业务栈帧方法名
     * @param businessLine      首个业务栈帧行号
     * @param businessFrames    业务栈帧行文本列表
     */
    public ExceptionStructure(String exceptionClass, String exceptionMessage,
            String rootCauseException, String rootCauseMessage, String loggerClass,
            String loggerMethod, Integer loggerLine, String businessClass,
            String businessMethod, Integer businessLine, List<String> businessFrames) {
        this.exceptionClass = exceptionClass;
        this.exceptionMessage = exceptionMessage;
        this.rootCauseException = rootCauseException;
        this.rootCauseMessage = rootCauseMessage;
        this.loggerClass = loggerClass;
        this.loggerMethod = loggerMethod;
        this.loggerLine = loggerLine;
        this.businessClass = businessClass;
        this.businessMethod = businessMethod;
        this.businessLine = businessLine;
        this.businessFrames = Collections.unmodifiableList(
                new ArrayList<String>(businessFrames));
    }

}
