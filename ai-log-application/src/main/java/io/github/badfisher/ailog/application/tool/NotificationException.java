package io.github.badfisher.ailog.application.tool;

/** 外部消息通知发送失败。 */
public final class NotificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
