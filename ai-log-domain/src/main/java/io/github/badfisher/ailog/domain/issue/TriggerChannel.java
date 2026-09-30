package io.github.badfisher.ailog.domain.issue;

/**
 * 错误触发通道。
 * <p>
 * 由 {@code TriggerChannelClassifier} 依据日志来源方法所在包或消息特征判定，
 * 用于识别错误入口是外部请求还是定时任务。
 */
public enum TriggerChannel {
    /** 通过 HTTP 接口进入的请求链路。 */
    HTTP,

    /** 消息队列消费链路。 */
    MQ,

    /** RPC 调用链路。 */
    RPC,

    /** 调度器触发的定时任务。 */
    SCHEDULER,

    /** 无法识别触发来源。 */
    UNKNOWN
}
