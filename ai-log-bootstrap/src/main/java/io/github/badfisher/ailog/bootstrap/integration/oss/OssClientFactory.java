package io.github.badfisher.ailog.bootstrap.integration.oss;

import com.aliyun.oss.OSS;

/** OSS 客户端创建边界，生产实现按操作创建并由调用方关闭客户端。 */
interface OssClientFactory {

    OSS create();
}
