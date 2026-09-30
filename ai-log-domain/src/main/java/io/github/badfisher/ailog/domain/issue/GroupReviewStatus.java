package io.github.badfisher.ailog.domain.issue;

/** 案件人工审核状态，审核结论关联具体 AI 执行记录。 */
public enum GroupReviewStatus {
    /** 待人工审核；与 AI 执行状态、案件处理状态独立。 */
    PENDING,
    /** 人工审核通过或人工忽略；Governance 记录操作人及当时的 AI Item。 */
    APPROVED,
    /** 人工审核驳回；保存驳回原因，不表示 AI 执行失败。 */
    REJECTED
}
