package com.shrestaexclusive.platform.order;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.shrestaexclusive.platform.auth.CustomerUnauthorizedException;
import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.mutation.IdempotencyConflictException;
import com.shrestaexclusive.platform.mutation.IdempotencyKeyRequiredException;
import com.shrestaexclusive.platform.mutation.MutationLockConflictException;
import com.shrestaexclusive.platform.mutation.MutationSafetyUnavailableException;

@RestControllerAdvice(assignableTypes = CustomerOrderController.class)
@SuppressWarnings({"unused", "java:S1144"})
public class CustomerOrderExceptionHandler {

    @ExceptionHandler(CustomerUnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @SuppressWarnings("unused")
    public ApiResponse<Void> unauthorized(CustomerUnauthorizedException exception) {
        return ApiResponse.failed("CUSTOMER_UNAUTHORIZED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @SuppressWarnings("unused")
    public ApiResponse<Void> invalidRequest(MethodArgumentNotValidException exception) {
        return ApiResponse.failed("INVALID_ORDER_REQUEST", "Enter valid cart, contact, delivery, payment, and address details.", traceId());
    }

    @ExceptionHandler(CustomerOrderPlacementException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @SuppressWarnings("unused")
    public ApiResponse<Void> placementFailed(CustomerOrderPlacementException exception) {
        return ApiResponse.failed("ORDER_PLACEMENT_FAILED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(CustomerOrderProductUnavailableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    @SuppressWarnings("unused")
    public ApiResponse<Void> productUnavailable(CustomerOrderProductUnavailableException exception) {
        return ApiResponse.failed("ORDER_PRODUCT_UNAVAILABLE", exception.getMessage(), traceId());
    }

    @ExceptionHandler(CustomerOrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @SuppressWarnings("unused")
    public ApiResponse<Void> notFound(CustomerOrderNotFoundException exception) {
        return ApiResponse.failed("ORDER_NOT_FOUND", exception.getMessage(), traceId());
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @SuppressWarnings("unused")
    public ApiResponse<Void> idempotencyRequired(IdempotencyKeyRequiredException exception) {
        return ApiResponse.failed("IDEMPOTENCY_KEY_REQUIRED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    @SuppressWarnings("unused")
    public ApiResponse<Void> idempotencyConflict(IdempotencyConflictException exception) {
        return ApiResponse.failed("IDEMPOTENCY_KEY_CONFLICT", exception.getMessage(), traceId());
    }

    @ExceptionHandler(MutationLockConflictException.class)
    @ResponseStatus(HttpStatus.LOCKED)
    @SuppressWarnings("unused")
    public ApiResponse<Void> mutationLocked(MutationLockConflictException exception) {
        return ApiResponse.failed("MUTATION_LOCKED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(MutationSafetyUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @SuppressWarnings("unused")
    public ApiResponse<Void> mutationSafetyUnavailable(MutationSafetyUnavailableException exception) {
        return ApiResponse.failed("MUTATION_SAFETY_UNAVAILABLE", exception.getMessage(), traceId());
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
