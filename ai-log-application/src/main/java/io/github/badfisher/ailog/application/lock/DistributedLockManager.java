package io.github.badfisher.ailog.application.lock;

/** 获取分布式锁的应用层端口。 */
public interface DistributedLockManager {

    /**
     * 按锁键获取分布式锁句柄。
     *
     * @param key 锁键
     * @return 分布式锁句柄
     */
    DistributedLock getLock(String key);
}
