package io.github.badfisher.ailog.domain.cleanup;

/** 本地文件清理任务状态，对应清理记录的 {@code status} 字段。 */
public enum FileCleanupStatus {
    /** ready 文件发布时创建，等待文件解析完成。 */
    WAITING_ANALYSIS,

    /** 解析成功，等待到达计划清理时间。 */
    WAITING,

    /** 正在删除。 */
    DELETING,

    /** 删除失败，等待下次自动重试。 */
    RETRY_WAITING,

    /** 已确认删除或文件不存在。 */
    DELETED,

    /** 需要人工处理。 */
    MANUAL_REQUIRED
}
