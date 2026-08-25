package com.shrestaexclusive.platform.sms.webhook;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shrestaexclusive.platform.common.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/sms/providers")
public class SmsProviderWebhookController {

    private final SmsProviderWebhookService webhookService;

    public SmsProviderWebhookController(SmsProviderWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/{provider}/webhook")
    public ResponseEntity<ApiResponse<SmsProviderWebhookResult>> receiveWebhook(
            @PathVariable String provider,
            @RequestHeader Map<String, String> headers,
            @RequestBody(required = false) String payload
    ) {
        Map<String, String> normalizedHeaders = headers.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toLowerCase(Locale.ROOT),
                        Map.Entry::getValue,
                        (left, right) -> right
                ));

        SmsProviderWebhookResult result = webhookService.process(provider, payload, normalizedHeaders);
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
