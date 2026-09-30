package io.github.badfisher.ailog.bootstrap.integration.oss;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.springframework.util.StringUtils;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import io.github.badfisher.ailog.application.tool.ObjectStorageException;
import io.github.badfisher.ailog.application.tool.ObjectStorageTool;
import io.github.badfisher.ailog.bootstrap.integration.oss.OssProperties;

/** 基于阿里云 OSS SDK 的本地文件上传适配器。 */
public final class OssObjectStorage implements ObjectStorageTool {

    /** 每 MB 包含的字节数。 */
    private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

    private final OssClientFactory ossClientFactory;
    private final OssProperties properties;
    private final int maxSize;

    /**
     * 使用默认 OSS SDK 客户端工厂创建适配器。
     *
     * @param propertiesValue OSS 配置
     * @param maxSizeValue 上传文件大小上限，单位 MB
     */
    public OssObjectStorage(OssProperties propertiesValue, int maxSizeValue) {
        this(new DefaultOssClientFactory(propertiesValue), propertiesValue, maxSizeValue);
    }

    /**
     * 创建只上传本地文件的 OSS 适配器。
     *
     * @param clientFactory OSS SDK 客户端工厂
     * @param propertiesValue OSS 配置
     * @param maxSizeValue 上传文件大小上限，单位 MB
     */
    public OssObjectStorage(OssClientFactory clientFactory, OssProperties propertiesValue,
            int maxSizeValue) {
        if (clientFactory == null || propertiesValue == null) {
            throw new IllegalArgumentException("OSS client factory and properties must not be null");
        }
        if (maxSizeValue <= 0) {
            throw new IllegalArgumentException("OSS max upload size must be positive");
        }
        requireText(propertiesValue.getEndpoint(),
                "OSS endpoint must be configured when upload is enabled");
        requireText(propertiesValue.getBucketName(),
                "OSS bucket name must be configured when upload is enabled");
        requireText(propertiesValue.getAccessKeyId(),
                "OSS access key ID must be configured when upload is enabled");
        requireText(propertiesValue.getAccessKeySecret(),
                "OSS access key secret must be configured when upload is enabled");
        ossClientFactory = clientFactory;
        properties = propertiesValue;
        maxSize = maxSizeValue;
    }

    @Override
    public String upload(Path sourceFile, String objectKey) {
        if (!Boolean.TRUE.equals(properties.isEnable())) {
            throw new IllegalStateException("OSS upload is disabled");
        }
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            throw new IllegalArgumentException("OSS source file must be an existing regular file");
        }
        validateSourceFileSize(sourceFile);
        String normalizedObjectKey = requireText(objectKey, "OSS object key must not be blank");
        OSS ossClient = null;
        try {
            ossClient = ossClientFactory.create();
            ossClient.putObject(properties.getBucketName(), normalizedObjectKey,
                    sourceFile.toFile(), metadataFor(sourceFile, normalizedObjectKey));
            return normalizedObjectKey;
        } catch (RuntimeException exception) {
            throw new ObjectStorageException("Failed to upload OSS object", exception);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    /** 为文本文件显式标记 UTF-8，避免浏览器按系统默认编码预览。 */
    private static ObjectMetadata metadataFor(Path sourceFile, String objectKey) {
        ObjectMetadata metadata = new ObjectMetadata();
        String fileName = sourceFile.getFileName().toString().toLowerCase(Locale.ROOT);
        String keyName = objectKey.toLowerCase(Locale.ROOT);
        if (hasTextExtension(fileName) || hasTextExtension(keyName)) {
            metadata.setContentType(textContentType(keyName));
            return metadata;
        }
        try {
            String contentType = Files.probeContentType(sourceFile);
            metadata.setContentType(StringUtils.hasText(contentType)
                    ? contentType : "application/octet-stream");
            return metadata;
        } catch (IOException exception) {
            throw new ObjectStorageException("Failed to detect OSS object content type", exception);
        }
    }

    private static boolean hasTextExtension(String fileName) {
        return fileName.endsWith(".txt") || fileName.endsWith(".log")
                || fileName.endsWith(".md") || fileName.endsWith(".csv")
                || fileName.endsWith(".json") || fileName.endsWith(".xml")
                || fileName.endsWith(".yml") || fileName.endsWith(".yaml");
    }

    private static String textContentType(String objectKey) {
        if (objectKey.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }
        if (objectKey.endsWith(".xml")) {
            return "application/xml; charset=UTF-8";
        }
        if (objectKey.endsWith(".csv")) {
            return "text/csv; charset=UTF-8";
        }
        if (objectKey.endsWith(".yml") || objectKey.endsWith(".yaml")) {
            return "application/x-yaml; charset=UTF-8";
        }
        return "text/plain; charset=UTF-8";
    }

    private void validateSourceFileSize(Path sourceFile) {
        try {
            long actualBytes = Files.size(sourceFile);
            long maximumBytes = maxSize * BYTES_PER_MEGABYTE;
            if (actualBytes > maximumBytes) {
                throw new IllegalArgumentException("OSS source file exceeds maximum size of "
                        + maxSize + " MB");
            }
        } catch (IOException exception) {
            throw new ObjectStorageException("Failed to inspect OSS source file size", exception);
        }
    }

    private static String requireText(String value, String errorMessage) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(errorMessage);
        }
        return value.trim();
    }
}
