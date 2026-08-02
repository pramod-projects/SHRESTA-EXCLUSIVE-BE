package com.shrestaexclusive.platform.storefront.admin;

import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_KEY_HEADER;
import static com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator;
import com.shrestaexclusive.platform.mutation.MutationFingerprint;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeService;
import jakarta.validation.Valid;
import java.time.Duration;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/storefront/home")
public class StorefrontAdminController {

    private static final String STOREFRONT_LOCK_KEY = "admin-storefront:home";
    private static final TypeReference<StorefrontHomeResponse> STOREFRONT_HOME_RESPONSE = new TypeReference<>() {
    };

    private final StorefrontHomeService service;
    private final StorefrontAdminAccessGuard accessGuard;
    private final IdempotentMutationCoordinator mutations;
    private final ObjectMapper objectMapper;

    public StorefrontAdminController(
            StorefrontHomeService service,
            StorefrontAdminAccessGuard accessGuard,
            IdempotentMutationCoordinator mutations,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.accessGuard = accessGuard;
        this.mutations = mutations;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<StorefrontHomeResponse>> getHome(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey
    ) {
        accessGuard.requireAdminKey(adminKey);
        return noStore(service.getHome());
    }

    @PatchMapping("/sections/{sectionKey}")
    public ResponseEntity<ApiResponse<StorefrontHomeResponse>> updateSection(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String sectionKey,
            @Valid @RequestBody StorefrontHomeSectionUpdateRequest request
    ) {
        accessGuard.requireAdminKey(adminKey);
        return noStore(mutations.run(
                "admin-storefront:update-section:" + sectionKey,
                idempotencyKey,
                MutationFingerprint.json(objectMapper, "PATCH", "/api/v1/admin/storefront/home/sections/" + sectionKey, request),
                STOREFRONT_LOCK_KEY,
                STOREFRONT_HOME_RESPONSE,
                () -> service.updateSection(request.toCommand(sectionKey))
        ));
    }

    @PatchMapping("/items/{itemKey}")
    public ResponseEntity<ApiResponse<StorefrontHomeResponse>> updateItem(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String itemKey,
            @Valid @RequestBody StorefrontHomeItemUpdateRequest request
    ) {
        accessGuard.requireAdminKey(adminKey);
        return noStore(mutations.run(
                "admin-storefront:update-item:" + itemKey,
                idempotencyKey,
                MutationFingerprint.json(objectMapper, "PATCH", "/api/v1/admin/storefront/home/items/" + itemKey, request),
                STOREFRONT_LOCK_KEY,
                STOREFRONT_HOME_RESPONSE,
                () -> service.updateItem(request.toCommand(itemKey))
        ));
    }

    private ResponseEntity<ApiResponse<StorefrontHomeResponse>> noStore(StorefrontHomeResponse response) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(ApiResponse.ok(response, traceId()));
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
