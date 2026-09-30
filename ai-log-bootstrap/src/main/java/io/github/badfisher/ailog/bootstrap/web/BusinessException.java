package io.github.badfisher.ailog.bootstrap.web;

/** A rejected business operation whose message is safe to return to the caller. */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}