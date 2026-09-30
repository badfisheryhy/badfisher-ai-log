package io.github.badfisher.ailog.domain.sync;

/** 日志文件解析状态，对应文件记录的 {@code parse_status} 字段。 */
public enum LogFileParseStatus {
    /** 等待解析。 */
    WAITING,

    /** 正在解析。 */
    PARSING,

    /** 解析完成。 */
    PARSED,

    /** 解析失败。 */
    FAILED
}
