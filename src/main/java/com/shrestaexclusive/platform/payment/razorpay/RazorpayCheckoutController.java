package com.shrestaexclusive.platform.payment.razorpay;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shrestaexclusive.platform.common.api.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/payments/razorpay")
public class RazorpayCheckoutController {

    private final RazorpayCheckoutService checkoutService;

    public RazorpayCheckoutController(RazorpayCheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping("/verify-payment")
    public ResponseEntity<ApiResponse<RazorpayVerifyPaymentResponse>> verifyPayment(
            @Valid @RequestBody RazorpayVerifyPaymentRequest request
    ) {
        RazorpayVerifyPaymentResponse response = checkoutService.verifyPayment(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(response, traceId()));
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
