package com.shrestaexclusive.platform.storefront.stores;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import java.time.Duration;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/storefront/stores")
public class StorefrontStoresController {

    private final StorefrontStoresService service;

    public StorefrontStoresController(StorefrontStoresService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<StorefrontStoresResponse>> getStores() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic().mustRevalidate())
                .body(ApiResponse.ok(service.getStores(), traceId()));
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
