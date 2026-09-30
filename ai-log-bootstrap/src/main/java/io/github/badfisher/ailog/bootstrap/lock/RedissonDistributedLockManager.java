package io.github.badfisher.ailog.bootstrap.lock;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import io.github.badfisher.ailog.application.lock.DistributedLock;
import io.github.badfisher.ailog.application.lock.DistributedLockManager;

/**
 * 基于 Redisson 的分布式锁适配器。
 */
public final class RedissonDistributedLockManager implements DistributedLockManager {

    /** Redisson 客户端。 */
    private final RedissonClient redissonClient;
    /** 固定锁租约毫秒数。 */
    private final long leaseTimeoutMillis;

    /**
     * 构造适配器。
     *
     * @param redissonClient Redisson 客户端
     * @param leaseTimeout   固定锁租约；到期后由 Redis 自动释放
     */
    public RedissonDistributedLockManager(RedissonClient redissonClient, Duration leaseTimeout) {
        if (redissonClient == null) {
            throw new IllegalArgumentException("redissonClient must not be null");
        }
        if (leaseTimeout == null || leaseTimeout.isZero() || leaseTimeout.isNegative()) {
            throw new IllegalArgumentException("leaseTimeout must be positive");
        }
        long timeoutMillis = leaseTimeout.toMillis();
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("leaseTimeout must be at least one millisecond");
        }
        this.redissonClient = redissonClient;
        leaseTimeoutMillis = timeoutMillis;
    }

    /**
     * 获取指定 key 对应的分布式锁。
     *
     * @param key 锁标识
     * @return 分布式锁
     */
    @Override
    public DistributedLock getLock(String key) {
        RLock lock = redissonClient.getLock(key);
        return new DistributedLock() {

            /**
             * 尝试获取锁。
             *
             * @return 获取成功返回 true
             */
            @Override
            public boolean tryLock() {
                try {
                    return lock.tryLock(0L, leaseTimeoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while acquiring distributed lock: " + key, ex);
                }
            }

            /**
             * 释放锁；仅当当前线程持有锁时执行。
             */
            @Override
            public void unlock() {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        };
    }
}
