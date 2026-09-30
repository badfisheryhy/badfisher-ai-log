package io.github.badfisher.ailog.bootstrap.integration.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import io.github.badfisher.ailog.bootstrap.integration.oss.OssProperties;

/** 基于当前 OSS 配置创建 SDK 客户端的默认工厂。 */
final class DefaultOssClientFactory implements OssClientFactory {

    private final OssProperties properties;

    DefaultOssClientFactory(OssProperties propertiesValue) {
        if (propertiesValue == null) {
            throw new IllegalArgumentException("OSS properties must not be null");
        }
        properties = propertiesValue;
    }

    @Override
    public OSS create() {
        return new OSSClientBuilder().build(properties.getEndpoint(),
                properties.getAccessKeyId(), properties.getAccessKeySecret());
    }
}
