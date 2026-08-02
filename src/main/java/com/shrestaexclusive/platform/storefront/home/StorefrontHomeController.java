package com.shrestaexclusive.platform.storefront.home;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import java.time.Duration;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/storefront/home")
public class StorefrontHomeController {

    private final StorefrontHomeService service;

    public StorefrontHomeController(StorefrontHomeService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<StorefrontHomeResponse>> getHome() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic().mustRevalidate())
                .body(ApiResponse.ok(service.getHome(), traceId()));
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
