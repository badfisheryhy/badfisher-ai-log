package io.github.badfisher.ailog.bootstrap.integration.dingtalk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Getter;
import lombok.Setter;

/** 钉钉群机器人配置。 */
@ConfigurationProperties("badfisher.dingtalk")
@Getter
@Setter
public class DingTalkRobotProperties {

    /** 是否启用机器人消息通知。 */
    private boolean enabled;
    /** 机器人 Webhook 地址，必须通过外部配置注入。 */
    private String webhookUrl = "";
    /** 机器人加签密钥；未开启加签时为空。 */
    private String secret = "";
    /** Markdown 默认标题。 */
    private String title = "Badfisher日志分析通知";

}
