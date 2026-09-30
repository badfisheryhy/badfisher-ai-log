package io.github.badfisher.ailog.bootstrap.web;

import lombok.Getter;

/** Stable JSON envelope for backend API responses. */
@Getter
public final class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;

    public ApiResponse(T data) {
        this(data, "OK");
    }

    public ApiResponse(T data, String message) {
        this(200, message, data);
    }

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}