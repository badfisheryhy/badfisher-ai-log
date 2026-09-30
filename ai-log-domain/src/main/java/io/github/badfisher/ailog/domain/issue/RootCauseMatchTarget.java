package io.github.badfisher.ailog.domain.issue;

/** 分类规则匹配的异常事实范围。 */
public enum RootCauseMatchTarget {
    /** 仅匹配顶层异常类和根因异常类。 */
    EXCEPTION,

    /** 仅匹配顶层异常消息、根因消息和日志 Header 消息。 */
    MESSAGE,

    /** 同时匹配异常类和消息。 */
    ALL
}
