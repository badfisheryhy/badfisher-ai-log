package io.github.badfisher.ailog.bootstrap.thread;

import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * MDC 上下文传递装饰器：任务提交时捕获调用线程的 MDC，执行时恢复，结束后还原。
 * <p>
 * 用于线程池任务，保证 traceId、TID 等诊断上下文跨线程传递，日志链路不中断。
 */
public class MdcTaskDecorator implements TaskDecorator {

    /**
     * 包装任务，传递提交时刻的 MDC 上下文。
     *
     * @param runnable 原始任务
     * @return 携带 MDC 上下文的任务
     */
    @Override
    public @NotNull Runnable decorate(@NotNull Runnable runnable) {
        final Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (context == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(context);
            }
            try {
                runnable.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
