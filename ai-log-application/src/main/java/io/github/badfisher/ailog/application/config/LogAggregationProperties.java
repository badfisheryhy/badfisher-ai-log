package io.github.badfisher.ailog.application.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 文件级流式事件聚合的内存边界和样本长度配置。 */
@Setter
@Getter
@ConfigurationProperties("badfisher.aggregation")
public class LogAggregationProperties {

    /** 单次内存中最多保留的聚合桶数量。 */
    private int maxBuckets = 1000;

    /** 单次内存聚合的保守估算字节上限，默认 8 MiB。 */
    private long maxEstimatedBytes = 8L * 1024L * 1024L;

    /** 单次 drain 前最多接收的 ERROR 数，避免低基数大文件长期不落批次。 */
    private long flushAfterAcceptedErrors = 10000L;

    /** 单条证据样本允许保存的最大字符数，单位为 Java UTF-16 char。 */
    private int maxSampleContentLength = 65536;

    /** 单个桶最多构造和比较的代表样本次数。 */
    private int maxSampleImproveAttempts = 5;

}
