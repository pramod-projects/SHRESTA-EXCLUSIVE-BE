package com.shrestaexclusive.platform.common.api;

import java.time.Instant;

public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        String traceId,
        Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(true, data, null, traceId, Instant.now());
    }

    public static <T> ApiResponse<T> failed(String code, String message, String traceId) {
        return new ApiResponse<>(false, null, new ApiError(code, message), traceId, Instant.now());
    }

    public record ApiError(String code, String message) {
    }
}
