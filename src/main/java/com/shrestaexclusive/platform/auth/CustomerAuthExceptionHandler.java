package com.shrestaexclusive.platform.auth;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.shrestaexclusive.platform.common.api.ApiResponse;

@RestControllerAdvice(assignableTypes = {CustomerAuthController.class, CustomerProfileController.class})
@SuppressWarnings("unused")
class CustomerAuthExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> invalidRequest(MethodArgumentNotValidException exception) {
        var parameter = exception.getParameter();
        var method = parameter == null ? null : parameter.getMethod();
        String methodName = method == null ? "" : method.getName();
        if ("register".equals(methodName)) {
            return ApiResponse.failed("INVALID_CUSTOMER_REGISTRATION", "Enter first name, last name, a valid email, and 10 digit Indian mobile number. Middle name is optional. For verification, provide a 6 digit OTP.", traceId());
        }
        return ApiResponse.failed("INVALID_CUSTOMER_LOGIN", "Enter a valid email or mobile number and a 6 digit OTP.", traceId());
    }

    @ExceptionHandler(CustomerRegistrationConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> registrationConflict(CustomerRegistrationConflictException exception) {
        return ApiResponse.failed("CUSTOMER_REGISTRATION_CONFLICT", exception.getMessage(), traceId());
    }

    @ExceptionHandler(CustomerRegistrationUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> registrationUnavailable(CustomerRegistrationUnavailableException exception) {
        return ApiResponse.failed("CUSTOMER_REGISTRATION_UNAVAILABLE", exception.getMessage(), traceId());
    }

    @ExceptionHandler(CustomerRegistrationVerificationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> registrationVerificationFailed(CustomerRegistrationVerificationException exception) {
        return ApiResponse.failed("CUSTOMER_REGISTRATION_OTP_INVALID", exception.getMessage(), traceId());
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
