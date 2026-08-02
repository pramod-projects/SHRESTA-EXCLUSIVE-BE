package com.shrestaexclusive.platform.support.chat;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/chat")
public class CustomerChatController {

    private final CustomerChatService service;

    public CustomerChatController(CustomerChatService service) {
        this.service = service;
    }

    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<CustomerChatMessageResponse>> createMessage(@Valid @RequestBody CustomerChatMessageRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(ApiResponse.ok(service.reply(request), traceId()));
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
