package io.github.badfisher.ailog.domain.analysis;

/** ERROR 文件分析任务状态，对应分析任务记录的 {@code status} 字段。 */
public enum AnalysisTaskStatus {
    /** 等待解析。 */
    WAITING,

    /** 正在解析。 */
    PARSING,

    /** 解析完成。 */
    SUCCESS,

    /** 解析失败；是否重跑由显式任务调度决定。 */
    FAILED
}
