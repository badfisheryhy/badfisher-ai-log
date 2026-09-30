package io.github.badfisher.ailog.domain.cleanup;

/** 文件清理任务触发原因，对应清理记录的 {@code trigger_reason} 字段。 */
public enum FileCleanupTriggerReason {
    /** ready 文件发布成功后自动创建。 */
    READY_PUBLISHED,

    /** 人工触发创建。 */
    MANUAL
}
