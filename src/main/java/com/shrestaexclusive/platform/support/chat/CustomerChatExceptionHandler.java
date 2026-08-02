package com.shrestaexclusive.platform.support.chat;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CustomerChatController.class)
class CustomerChatExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> invalidRequest(MethodArgumentNotValidException exception) {
        return ApiResponse.failed("INVALID_CUSTOMER_CHAT_MESSAGE", "Enter a chat message up to 1000 characters.", traceId());
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
