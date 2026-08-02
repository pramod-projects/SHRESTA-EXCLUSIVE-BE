package com.shrestaexclusive.platform.auth;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/profile")
public class CustomerProfileController {

    private final CustomerAuthService service;

    public CustomerProfileController(CustomerAuthService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CustomerProfileResponse>> profile(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(ApiResponse.ok(service.profile(CustomerAuthController.bearerToken(authorization)), traceId()));
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
