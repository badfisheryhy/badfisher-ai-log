package io.github.badfisher.ailog.bootstrap.integration.oss;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional object storage connection; secrets are supplied by the environment. */
@Getter
@Setter
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssProperties {

    private boolean enable;
    private String endpoint;
    private String bucketName;
    private String accessKeyId;
    private String accessKeySecret;
}