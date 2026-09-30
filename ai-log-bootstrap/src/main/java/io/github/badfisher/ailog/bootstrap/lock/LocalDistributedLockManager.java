package io.github.badfisher.ailog.bootstrap.lock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import io.github.badfisher.ailog.application.lock.DistributedLock;
import io.github.badfisher.ailog.application.lock.DistributedLockManager;

/**
 * 基于 JVM 内 {@link ReentrantLock} 的锁实现，作为无 Redis 环境的降级兜底。
 *
 * <p>【可调整点/语义警示】本实现只能保证<b>单实例</b>内的互斥（对应设计文档第 45 节
 * “V1 可使用本机锁，但必须明确只能保证单实例”）。多实例部署时必须提供 Redis，
 * 由 {@link RedissonDistributedLockManager} 提供真正的分布式互斥；生产环境如误用本实现，
 * 同一模块的同日日志可能被多个实例同时同步。仅用于本地开发、测试上下文加载等场景。</p>
 */
public final class LocalDistributedLockManager implements DistributedLockManager {

    /** 锁键到可重入锁的映射。 */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<String, ReentrantLock>();

    @Override
    public DistributedLock getLock(final String key) {
        final ReentrantLock lock = locks.get(key);
        if (lock != null) {
            return handle(lock);
        }
        ReentrantLock created = locks.putIfAbsent(key, new ReentrantLock());
        return handle(created == null ? locks.get(key) : created);
    }

    /** 包装可重入锁为应用层锁句柄。 */
    private static DistributedLock handle(final ReentrantLock lock) {
        return new DistributedLock() {
            @Override
            public boolean tryLock() {
                return lock.tryLock();
            }

            @Override
            public void unlock() {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        };
    }
}
