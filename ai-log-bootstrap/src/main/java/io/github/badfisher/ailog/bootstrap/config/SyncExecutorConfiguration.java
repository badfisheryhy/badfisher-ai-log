package io.github.badfisher.ailog.bootstrap.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.github.badfisher.ailog.bootstrap.thread.MdcTaskDecorator;

/** 日志同步并发线程池装配：有界队列、调用者兜底、MDC 上下文跨线程传递。 */
@Configuration
public class SyncExecutorConfiguration {

    /** 模块级同步线程池大小；限制同一批次并行处理的模块数。 */
    private static final int MODULE_SYNC_POOL_SIZE = 2;

    /** 模块级并发同步线程池队列容量。 */
    private static final int MODULE_SYNC_QUEUE_CAPACITY = 64;

    /** 文件级同步线程池大小；限制单个模块并行拉取的日志文件数。 */
    private static final int FILE_SYNC_POOL_SIZE = 2;

    /** 文件级并发同步线程池队列容量。 */
    private static final int FILE_SYNC_QUEUE_CAPACITY = 32;

    /** 关闭时等待任务完成的最大时间（秒）。 */
    private static final int AWAIT_TERMINATION_SECONDS = 60;

    /**
     * 注册模块级并发同步线程池。
     *
     * @return 模块级线程池
     */
    @Bean(name = "moduleSyncExecutor")
    public ThreadPoolTaskExecutor moduleSyncExecutor() {
        return buildExecutor(MODULE_SYNC_POOL_SIZE, MODULE_SYNC_QUEUE_CAPACITY, "module-sync-");
    }

    /**
     * 注册文件级并发同步线程池。
     *
     * @return 文件级线程池
     */
    @Bean(name = "fileSyncExecutor")
    public ThreadPoolTaskExecutor fileSyncExecutor() {
        return buildExecutor(FILE_SYNC_POOL_SIZE, FILE_SYNC_QUEUE_CAPACITY, "file-sync-");
    }

    /**
     * 创建带 MDC 传递、有界队列与调用者兜底的线程池；容器关闭时随 DisposableBean 优雅回收。
     *
     * @param poolSize       核心与最大线程数
     * @param queueCapacity  队列容量
     * @param threadPrefix   线程名前缀
     * @return 线程池
     */
    private ThreadPoolTaskExecutor buildExecutor(int poolSize, int queueCapacity, String threadPrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadPrefix);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        return executor;
    }
}
