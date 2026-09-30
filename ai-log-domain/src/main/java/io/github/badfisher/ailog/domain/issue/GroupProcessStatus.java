package io.github.badfisher.ailog.domain.issue;

/** Group 当前人工处理阶段；审核通过进入处理中，忽略后停止自动分析。 */
public enum GroupProcessStatus {
    /** 待处理；新 Group 及审核驳回后的自动分析范围。 */
    PENDING,
    /** 审核通过后进入；责任人和认领人可在此阶段继续设置。 */
    PROCESSING,
    /** 已人工解决，等待确认处理结果。 */
    RESOLVED,
    /** 处理结果验收通过；后续同指纹 Event 不自动重开。 */
    COMPLETED,
    /** 人工忽略；后续 Event 仍累计，但不触发自动分析。 */
    IGNORED
}
