package io.github.badfisher.ailog.domain.sync;

/** 同步任务状态，对应同步任务及模块任务记录的 {@code status} 字段。 */
public enum SyncTaskStatus {
    /** 等待执行。 */
    PENDING,

    /** 正在执行。 */
    RUNNING,

    /** 全部模块同步成功。 */
    SUCCESS,

    /** 部分模块成功、部分失败或锁冲突。 */
    PARTIAL_SUCCESS,

    /** 全部模块同步失败。 */
    FAILED,

    /** 模块因其他执行者持锁而跳过，不计为失败。 */
    CONFLICT
}
