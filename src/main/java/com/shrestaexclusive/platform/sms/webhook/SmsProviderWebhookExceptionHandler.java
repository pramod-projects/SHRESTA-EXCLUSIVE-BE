package com.shrestaexclusive.platform.sms.webhook;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.shrestaexclusive.platform.common.api.ApiResponse;

@RestControllerAdvice(assignableTypes = SmsProviderWebhookController.class)
@SuppressWarnings("unused")
class SmsProviderWebhookExceptionHandler {

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @SuppressWarnings("unused")
    ApiResponse<Void> unauthorizedWebhook(SecurityException exception) {
        return ApiResponse.failed("SMS_WEBHOOK_UNAUTHORIZED", exception.getMessage(), traceId());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @SuppressWarnings("unused")
    ApiResponse<Void> invalidProvider(IllegalArgumentException exception) {
        return ApiResponse.failed("INVALID_SMS_WEBHOOK_PROVIDER", exception.getMessage(), traceId());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @SuppressWarnings("unused")
    ApiResponse<Void> invalidWebhook(Exception exception) {
        return ApiResponse.failed("SMS_WEBHOOK_INVALID", exception.getMessage(), traceId());
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
