package com.shrestaexclusive.platform.asset;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.mutation.IdempotencyConflictException;
import com.shrestaexclusive.platform.mutation.IdempotencyKeyRequiredException;
import com.shrestaexclusive.platform.mutation.MutationLockConflictException;
import com.shrestaexclusive.platform.mutation.MutationSafetyUnavailableException;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminUnauthorizedException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminAssetController.class)
class AdminAssetExceptionHandler {

    @ExceptionHandler(StorefrontAdminUnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> unauthorized(StorefrontAdminUnauthorizedException exception) {
        return ApiResponse.failed("ADMIN_UNAUTHORIZED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(AssetNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> notFound(AssetNotFoundException exception) {
        return ApiResponse.failed("ASSET_NOT_FOUND", exception.getMessage(), traceId());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> invalid(RuntimeException exception) {
        return ApiResponse.failed("INVALID_ASSET_REQUEST", exception.getMessage(), traceId());
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> idempotencyRequired(IdempotencyKeyRequiredException exception) {
        return ApiResponse.failed("IDEMPOTENCY_KEY_REQUIRED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> idempotencyConflict(IdempotencyConflictException exception) {
        return ApiResponse.failed("IDEMPOTENCY_KEY_CONFLICT", exception.getMessage(), traceId());
    }

    @ExceptionHandler(MutationLockConflictException.class)
    @ResponseStatus(HttpStatus.LOCKED)
    ApiResponse<Void> mutationLocked(MutationLockConflictException exception) {
        return ApiResponse.failed("MUTATION_LOCKED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(MutationSafetyUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> mutationSafetyUnavailable(MutationSafetyUnavailableException exception) {
        return ApiResponse.failed("MUTATION_SAFETY_UNAVAILABLE", exception.getMessage(), traceId());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiResponse<Void> processingFailed(IllegalStateException exception) {
        return ApiResponse.failed("ASSET_PROCESSING_FAILED", exception.getMessage(), traceId());
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
