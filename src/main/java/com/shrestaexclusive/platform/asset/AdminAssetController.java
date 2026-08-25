package com.shrestaexclusive.platform.asset;

import java.util.Map;
import java.util.Set;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator;
import static com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER;
import com.shrestaexclusive.platform.mutation.MutationFingerprint;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_KEY_HEADER;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/assets")
public class AdminAssetController {

        private static final Set<String> ASSET_ROLES = Set.of("CHANGE_SUBMITTER", "CHANGE_APPROVER", "CHANGE_MANAGER", "CHANGE_ADMIN");
        private static final TypeReference<MediaUploadAuthorizationResponse> UPLOAD_AUTHORIZATION_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<AssetResponse> ASSET_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<AssetSearchResponse> ASSET_SEARCH_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<Void> VOID_RESPONSE = new TypeReference<>() {
    };

    private final AssetService service;
    private final StorefrontAdminAccessGuard accessGuard;
    private final IdempotentMutationCoordinator mutations;
    private final ObjectMapper objectMapper;

    public AdminAssetController(
            AssetService service,
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
    public ResponseEntity<ApiResponse<AssetSearchResponse>> search(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String categoryFamilyKey,
            @RequestParam(required = false) String categoryProductTypeKey,
            @RequestParam(required = false) String productSku,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size
    ) {
        accessGuard.requireRole(adminKey, adminRole, ASSET_ROLES);
        return noStore(service.search(query, categoryFamilyKey, categoryProductTypeKey, productSku, status, page, size));
    }

    @GetMapping("/storefront-unreferenced")
    public ResponseEntity<ApiResponse<StorefrontUnreferencedAssetsResponse>> storefrontUnreferenced(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size
    ) {
        accessGuard.requireRole(adminKey, adminRole, ASSET_ROLES);
        return noStore(service.searchStorefrontUnreferenced(query, status, page, size));
    }

    @GetMapping("/{assetKey}")
    public ResponseEntity<ApiResponse<AssetResponse>> get(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @PathVariable String assetKey
    ) {
        accessGuard.requireRole(adminKey, adminRole, ASSET_ROLES);
        return noStore(service.get(assetKey));
    }

    @PostMapping("/upload-authorizations")
    public ResponseEntity<ApiResponse<MediaUploadAuthorizationResponse>> authorizeUpload(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = "X-SHRESTA-ADMIN-ACTOR", required = false) String actor,
            @Valid @org.springframework.web.bind.annotation.RequestBody MediaUploadAuthorizationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, ASSET_ROLES);
        return noStore(mutations.run(
                "admin-assets:authorize-upload",
                idempotencyKey,
                MutationFingerprint.json(objectMapper, "POST", "/api/v1/admin/assets/upload-authorizations", request),
                "admin-assets:authorize-upload:" + idempotencyKey,
                UPLOAD_AUTHORIZATION_RESPONSE,
                () -> service.authorizeUpload(request, actor)
        ));
    }

    @PostMapping("/product-media-reservations")
    public ResponseEntity<ApiResponse<ProductMediaReservationResponse>> reserveProductMedia(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = "X-SHRESTA-ADMIN-ACTOR", required = false) String actor
    ) {
        accessGuard.requireRole(adminKey, adminRole, ASSET_ROLES);
        return noStore(mutations.run(
                "admin-assets:reserve-product-media",
                idempotencyKey,
                MutationFingerprint.json(objectMapper, "POST", "/api/v1/admin/assets/product-media-reservations", java.util.Map.of()),
                "admin-assets:reserve-product-media:" + idempotencyKey,
                new TypeReference<ProductMediaReservationResponse>() { },
                () -> service.reserveProductMedia(actor)
        ));
    }

    @PostMapping("/upload-completions")
    public ResponseEntity<ApiResponse<AssetResponse>> completeUpload(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = "X-SHRESTA-ADMIN-ACTOR", required = false) String actor,
            @Valid @org.springframework.web.bind.annotation.RequestBody MediaUploadCompletionRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, ASSET_ROLES);
        return noStore(mutations.run(
                "admin-assets:complete-upload:" + request.mediaId(),
                idempotencyKey,
                MutationFingerprint.json(objectMapper, "POST", "/api/v1/admin/assets/upload-completions", request),
                "admin-assets:complete-upload:" + request.mediaId(),
                ASSET_RESPONSE,
                () -> service.completeUpload(request.mediaId(), actor)
        ));
    }

    @PatchMapping("/{assetKey}")
    public ResponseEntity<ApiResponse<AssetResponse>> updateMetadata(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String assetKey,
            @Valid @org.springframework.web.bind.annotation.RequestBody AssetMetadataUpdateRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, ASSET_ROLES);
        return noStore(mutations.run(
                "admin-assets:update-metadata:" + assetKey,
                idempotencyKey,
                MutationFingerprint.json(objectMapper, "PATCH", "/api/v1/admin/assets/" + assetKey, request),
                "admin-assets:asset:" + assetKey,
                ASSET_RESPONSE,
                () -> service.updateMetadata(assetKey, request)
        ));
    }

    @PostMapping("/bulk/category-assignment")
    public ResponseEntity<ApiResponse<AssetSearchResponse>> bulkAssignCategory(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @org.springframework.web.bind.annotation.RequestBody BulkCategoryAssignmentRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, ASSET_ROLES);
        return noStore(mutations.run(
                "admin-assets:bulk-category-assignment",
                idempotencyKey,
                MutationFingerprint.json(objectMapper, "POST", "/api/v1/admin/assets/bulk/category-assignment", request),
                "admin-assets:bulk-category-assignment",
                ASSET_SEARCH_RESPONSE,
                () -> service.bulkAssignCategory(request)
        ));
    }

    @DeleteMapping("/{assetKey}")
    public ResponseEntity<ApiResponse<Void>> archive(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String assetKey
    ) {
        accessGuard.requireRole(adminKey, adminRole, ASSET_ROLES);
        return noStore(mutations.run(
                "admin-assets:archive:" + assetKey,
                idempotencyKey,
                MutationFingerprint.json(objectMapper, "DELETE", "/api/v1/admin/assets/" + assetKey, Map.of("assetKey", assetKey)),
                "admin-assets:asset:" + assetKey,
                VOID_RESPONSE,
                () -> {
                    service.archive(assetKey);
                    return null;
                }
        ));
    }

    private <T> ResponseEntity<ApiResponse<T>> noStore(T data) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(ApiResponse.ok(data, traceId()));
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }

}
