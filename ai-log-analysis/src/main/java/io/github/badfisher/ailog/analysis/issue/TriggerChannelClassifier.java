package io.github.badfisher.ailog.analysis.issue;

import io.github.badfisher.ailog.domain.issue.TriggerChannel;
import io.github.badfisher.ailog.domain.log.LogEvent;

/** 只使用线程与明确入口 logger 证据，禁止全文 http/scheduled 模糊命中。 */
public final class TriggerChannelClassifier {

    private final java.util.function.Predicate<LogEvent> schedulerEvidence;

    /** 默认只识别通用调度特征；具体框架证据由适配器注入。 */
    public TriggerChannelClassifier() {
        this(event -> false);
    }

    public TriggerChannelClassifier(java.util.function.Predicate<LogEvent> schedulerEvidence) {
        this.schedulerEvidence = java.util.Objects.requireNonNull(schedulerEvidence);
    }

    /**
     * 根据线程名与 logger 类名判断事件触发渠道。
     *
     * @param event 日志事件
     * @return 触发渠道，无法判断时为 UNKNOWN
     */
    public TriggerChannel classify(LogEvent event) {
        String threadName = lower(event.getThreadName());
        String loggerClass = lower(event.getLoggerClass());
        if (isMessageQueue(threadName, loggerClass)) {
            return TriggerChannel.MQ;
        }
        if (schedulerEvidence.test(event) || threadName.startsWith("scheduling-")
                || loggerClass.endsWith("jobhandler")
                || loggerClass.contains("scheduledmethodrunnable")) {
            return TriggerChannel.SCHEDULER;
        }
        if (loggerClass.contains(".rpc.") || loggerClass.contains("dubbo")) {
            return TriggerChannel.RPC;
        }
        if (threadName.startsWith("http-nio-") || threadName.startsWith("xnio-")
                || loggerClass.endsWith("controller")
                || loggerClass.contains("dispatcherservlet")
                || loggerClass.endsWith("webfilter")) {
            return TriggerChannel.HTTP;
        }
        return TriggerChannel.UNKNOWN;
    }

    /**
     * 判断是否由消息队列触发。
     *
     * @param threadName  线程名
     * @param loggerClass logger 类名
     * @return 命中消息队列特征时为 true
     */
    private static boolean isMessageQueue(String threadName, String loggerClass) {
        return threadName.contains("rabbitlistenerendpointcontainer")
                || loggerClass.contains("rabbitlistener")
                || loggerClass.contains("messagelistener")
                || loggerClass.contains(".mq.") && loggerClass.endsWith("receiver");
    }

    /**
     * 空值安全的小写转换。
     *
     * @param value 原始值
     * @return 小写字符串
     */
    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase();
    }
}
