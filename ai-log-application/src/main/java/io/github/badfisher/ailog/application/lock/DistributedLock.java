package io.github.badfisher.ailog.application.lock;

/** 分布式互斥锁句柄。 */
public interface DistributedLock {

    /**
     * 尝试获取锁，不阻塞等待。
     *
     * @return 获取成功时为 true
     */
    boolean tryLock();

    /** 释放锁，仅在持有锁时调用。 */
    void unlock();
}
