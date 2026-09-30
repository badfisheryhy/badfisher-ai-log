package io.github.badfisher.ailog.bootstrap.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.badfisher.ailog.application.tool.NotificationTool;
import io.github.badfisher.ailog.application.tool.ObjectStorageTool;
import io.github.badfisher.ailog.bootstrap.integration.dingtalk.DingTalkRobotNotifier;
import io.github.badfisher.ailog.bootstrap.integration.dingtalk.DingTalkRobotProperties;
import io.github.badfisher.ailog.bootstrap.integration.oss.OssObjectStorage;
import io.github.badfisher.ailog.bootstrap.integration.oss.OssProperties;

/** 对象存储和外部消息通知的独立基础设施装配。 */
@Configuration
@EnableConfigurationProperties({DingTalkRobotProperties.class, OssProperties.class})
public class ExternalDeliveryConfiguration {

    /** 显式启用 OSS 后暴露厂商无关的文件传输端口。 */
    @Bean
    @ConditionalOnMissingBean(ObjectStorageTool.class)
    @ConditionalOnProperty(prefix = "aliyun.oss", name = "enable", havingValue = "true")
    public ObjectStorageTool objectStorageTool(OssProperties properties,
            @Value("${aliyun.oss.max-size:30}") int maxSize) {
        return new OssObjectStorage(properties, maxSize);
    }

    /** 显式启用钉钉机器人后暴露厂商无关的消息通知端口。 */
    @Bean
    @ConditionalOnMissingBean(NotificationTool.class)
    @ConditionalOnProperty(prefix = "badfisher.dingtalk", name = "enabled",
            havingValue = "true")
    public NotificationTool notificationTool(DingTalkRobotProperties properties) {
        return new DingTalkRobotNotifier(properties);
    }
}
