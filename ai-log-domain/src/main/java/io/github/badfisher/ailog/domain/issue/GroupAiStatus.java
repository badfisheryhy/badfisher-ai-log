package io.github.badfisher.ailog.domain.issue;

/** 案件当前 AI 执行状态，与人工审核及处理状态独立。 */
public enum GroupAiStatus {
    /** 等待 AI 分析；不代表等待人工审核或认领。 */
    WAITING,
    /** 当前 AI 分析正在执行；与人工处理中的 PROCESSING 含义不同。 */
    PROCESSING,
    /** AI 分析已完成；不代表人工审核通过或案件已解决。 */
    COMPLETED,
    /** AI 执行失败；不表示人工审核驳回。 */
    FAILED
}
