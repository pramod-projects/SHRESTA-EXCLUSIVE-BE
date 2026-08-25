package com.shrestaexclusive.platform.admin.testusers;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminUnauthorizedException;

@RestControllerAdvice(assignableTypes = AdminTestUserController.class)
class AdminTestUserExceptionHandler {

    @ExceptionHandler(StorefrontAdminUnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> unauthorized(StorefrontAdminUnauthorizedException exception) {
        return ApiResponse.failed("ADMIN_UNAUTHORIZED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(TestUserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> notFound(TestUserNotFoundException exception) {
        return ApiResponse.failed("TEST_USER_NOT_FOUND", exception.getMessage(), traceId());
    }

    @ExceptionHandler(TestUserOtpAlreadyRevealedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> alreadyRevealed(TestUserOtpAlreadyRevealedException exception) {
        return ApiResponse.failed("TEST_USER_OTP_ALREADY_REVEALED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> invalidRequest(IllegalArgumentException exception) {
        return ApiResponse.failed("INVALID_TEST_USER_REQUEST", exception.getMessage(), traceId());
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
