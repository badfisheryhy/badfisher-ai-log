package io.github.badfisher.ailog.application.tool;

import java.nio.file.Path;

/** 对象存储上传端口，应用编排不依赖具体对象存储 SDK、Bucket 或账号配置。 */
public interface ObjectStorageTool {

    /**
     * 上传本地文件。上传失败必须向调用方抛出异常。
     *
     * @param sourceFile 本地待上传文件
     * @param objectKey 对象存储键
     * @return 服务端实际使用的对象键
     */
    String upload(Path sourceFile, String objectKey);

}
