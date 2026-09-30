package io.github.badfisher.ailog.domain.sync;

/** 同步任务触发来源。*/
public enum SyncTriggerType {
    /** 手工通过接口或命令行触发。 */
    MANUAL,

    /** 由调度器触发。 */
    SCHEDULER
}
