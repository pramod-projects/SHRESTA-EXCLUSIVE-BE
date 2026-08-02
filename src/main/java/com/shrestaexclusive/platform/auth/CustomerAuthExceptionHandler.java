package com.shrestaexclusive.platform.auth;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {CustomerAuthController.class, CustomerProfileController.class})
class CustomerAuthExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> invalidRequest(MethodArgumentNotValidException exception) {
        return ApiResponse.failed("INVALID_CUSTOMER_LOGIN", "Enter a valid email or mobile number and a 6 digit OTP.", traceId());
    }

    @ExceptionHandler(CustomerLoginFailedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> loginFailed(CustomerLoginFailedException exception) {
        return ApiResponse.failed("CUSTOMER_LOGIN_FAILED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(CustomerLoginUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> loginUnavailable(CustomerLoginUnavailableException exception) {
        return ApiResponse.failed("CUSTOMER_LOGIN_UNAVAILABLE", exception.getMessage(), traceId());
    }

    @ExceptionHandler(CustomerUnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> unauthorized(CustomerUnauthorizedException exception) {
        return ApiResponse.failed("CUSTOMER_UNAUTHORIZED", exception.getMessage(), traceId());
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
