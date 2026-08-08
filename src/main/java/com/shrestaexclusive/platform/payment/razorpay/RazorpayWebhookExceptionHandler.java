package com.shrestaexclusive.platform.payment.razorpay;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.order.CustomerOrderNotFoundException;

@RestControllerAdvice(assignableTypes = RazorpayWebhookController.class)
class RazorpayWebhookExceptionHandler {

    @ExceptionHandler(RazorpayWebhookValidationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> unauthorized(RazorpayWebhookValidationException exception) {
        return ApiResponse.failed("WEBHOOK_UNAUTHORIZED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(CustomerOrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> orderNotFound(CustomerOrderNotFoundException exception) {
        return ApiResponse.failed("ORDER_NOT_FOUND", exception.getMessage(), traceId());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> invalidPayload(Exception exception) {
        return ApiResponse.failed("WEBHOOK_INVALID", exception.getMessage(), traceId());
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
