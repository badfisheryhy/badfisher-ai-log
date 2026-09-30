package io.github.badfisher.ailog.application.config;

/** 同步互斥锁模式。 */
public enum LogSyncLockMode {
    /** 由部署适配器提供跨实例互斥。 */
    DISTRIBUTED,

    /** JVM 本地锁，仅适用于单实例部署。 */
    LOCAL
}
