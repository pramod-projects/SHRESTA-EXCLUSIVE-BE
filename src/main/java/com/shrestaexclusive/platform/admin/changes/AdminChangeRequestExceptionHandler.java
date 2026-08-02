package com.shrestaexclusive.platform.admin.changes;

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

@RestControllerAdvice(assignableTypes = AdminChangeRequestController.class)
class AdminChangeRequestExceptionHandler {

    @ExceptionHandler(StorefrontAdminUnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> unauthorized(StorefrontAdminUnauthorizedException exception) {
        return ApiResponse.failed("ADMIN_UNAUTHORIZED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(AdminChangeRequestNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> notFound(AdminChangeRequestNotFoundException exception) {
        return ApiResponse.failed("ADMIN_CHANGE_REQUEST_NOT_FOUND", exception.getMessage(), traceId());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class, UnsupportedAdminChangeRequestException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> invalidRequest(Exception exception) {
        return ApiResponse.failed("INVALID_ADMIN_CHANGE_REQUEST", exception.getMessage(), traceId());
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

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
