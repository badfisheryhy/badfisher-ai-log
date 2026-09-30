package io.github.badfisher.ailog.domain.sync;

/** 日志文件同步状态，对应文件记录的 {@code sync_status} 字段。 */
public enum LogFileSyncStatus {
    /** 等待同步。 */
    PENDING,

    /** 正在同步。 */
    SYNCING,

    /** 已发布到 ready 目录。 */
    READY,

    /** 同步失败。 */
    FAILED
}
