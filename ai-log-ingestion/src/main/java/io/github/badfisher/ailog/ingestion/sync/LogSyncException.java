package io.github.badfisher.ailog.ingestion.sync;

import lombok.Getter;

/** 日志同步配置或执行失败。 */
@Getter
public class LogSyncException extends RuntimeException {

    /** 失败类型编码。
     * -- GETTER --
     * 返回失败类型编码。
     */
    private final SyncErrorCode errorCode;

    /** 构造无类型编码的异常，默认归类为 {@link SyncErrorCode#RSYNC_FAILED}。 */
    public LogSyncException(String message) {
        this(SyncErrorCode.RSYNC_FAILED, message, null);
    }

    /** 构造带根因的异常，默认归类为 {@link SyncErrorCode#RSYNC_FAILED}。 */
    public LogSyncException(String message, Throwable cause) {
        this(SyncErrorCode.RSYNC_FAILED, message, cause);
    }

    /** 构造带类型编码的异常。 */
    public LogSyncException(SyncErrorCode code, String message) {
        this(code, message, null);
    }

    /** 构造带类型编码与根因的异常。 */
    public LogSyncException(SyncErrorCode code, String message, Throwable cause) {
        super(message, cause);
        errorCode = code;
    }

}
