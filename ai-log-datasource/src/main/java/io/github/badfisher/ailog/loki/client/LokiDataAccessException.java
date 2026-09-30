package io.github.badfisher.ailog.loki.client;

/**
 * Loki 数据访问异常，表示查询、解析或重试过程中出现的不可恢复错误。
 * <p>
 * 应用层捕获本异常后按窗口标记 FAILED，并按重试策略决定是否重新执行。
 */
public class LokiDataAccessException extends RuntimeException {

    /** 构造无根因异常。 */
    public LokiDataAccessException(String message) {
        super(message);
    }

    /** 构造带根因异常。 */
    public LokiDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
