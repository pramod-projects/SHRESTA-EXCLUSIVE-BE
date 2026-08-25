package com.shrestaexclusive.platform.payment.razorpay;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.shrestaexclusive.platform.common.api.ApiResponse;

@RestControllerAdvice(assignableTypes = RazorpayCheckoutController.class)
@SuppressWarnings("unused")
class RazorpayCheckoutExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> invalidRequest(MethodArgumentNotValidException exception) {
        return ApiResponse.failed("INVALID_RAZORPAY_REQUEST", "Enter a valid amount, currency, and receipt.", traceId());
    }

    @ExceptionHandler(RazorpayCheckoutConfigurationException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> missingConfig(RazorpayCheckoutConfigurationException exception) {
        return ApiResponse.failed("RAZORPAY_NOT_CONFIGURED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(RazorpayCheckoutAuthException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> authFailure(RazorpayCheckoutAuthException exception) {
        return ApiResponse.failed("RAZORPAY_AUTH_FAILED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(RazorpayCheckoutSignatureMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> signatureMismatch(RazorpayCheckoutSignatureMismatchException exception) {
        return ApiResponse.failed("RAZORPAY_SIGNATURE_MISMATCH", exception.getMessage(), traceId());
    }

    @ExceptionHandler(RazorpayCheckoutUpstreamException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> upstreamFailure(RazorpayCheckoutUpstreamException exception) {
        return ApiResponse.failed("RAZORPAY_UPSTREAM_FAILURE", exception.getMessage(), traceId());
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
