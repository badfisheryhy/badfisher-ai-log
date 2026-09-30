package io.github.badfisher.ailog.bootstrap.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 单实例启动恢复配置；只有部署方显式确认旧进程停止后才允许开启。 */
@Getter
@Setter
@ConfigurationProperties("badfisher.recovery")
public class StartupRecoveryProperties {

    /** 是否启用启动恢复，默认关闭。 */
    private boolean enabled;
    /** 本部署负责恢复的环境编码。 */
    private String environment;
    /** 本部署负责恢复的系统编码。 */
    private String systemCode;
    /** 单次扫描上限，避免一次加载全库。 */
    private int batchSize = 100;
}
