package com.shrestaexclusive.platform.payment.razorpay;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shrestaexclusive.platform.common.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/payments/razorpay")
public class RazorpayWebhookController {

    private static final String SIGNATURE_HEADER = "X-Razorpay-Signature";
    private static final String EVENT_ID_HEADER = "X-Razorpay-Event-Id";

    private final RazorpayWebhookService webhookService;

    public RazorpayWebhookController(RazorpayWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<ApiResponse<RazorpayWebhookResult>> receiveWebhook(
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(value = EVENT_ID_HEADER, required = false) String eventId,
            @RequestBody(required = false) String payload
    ) {
        RazorpayWebhookResult result = webhookService.process(payload, signature, eventId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(result, traceId()));
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
