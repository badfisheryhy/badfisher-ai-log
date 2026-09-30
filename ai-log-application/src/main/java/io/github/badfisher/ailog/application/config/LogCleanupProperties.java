package io.github.badfisher.ailog.application.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本地 ready 日志文件清理配置。 */
@Setter
@Getter
@ConfigurationProperties("badfisher.cleanup")
public class LogCleanupProperties {

    /** 是否允许真实删除文件；默认关闭，避免未配置环境误删。 */
    private boolean enabled;
    /** 补偿 Job 单次最大处理文件数。 */
    private int batchSize = 20;
    /** 自动删除失败后的最大尝试次数。 */
    private int maxRetryCount = 3;
    /** 自动删除失败后的重试间隔分钟数。 */
    private int retryDelayMinutes = 30;

}
