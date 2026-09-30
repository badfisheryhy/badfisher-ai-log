package io.github.badfisher.ailog.application.tool;

/** 对象存储上传失败。 */
public final class ObjectStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
