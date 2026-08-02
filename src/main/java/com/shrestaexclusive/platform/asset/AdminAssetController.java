package com.shrestaexclusive.platform.asset;

import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_KEY_HEADER;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER;
import static com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator;
import com.shrestaexclusive.platform.mutation.MutationFingerprint;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/assets")
public class AdminAssetController {

    private static final Set<String> ASSET_ROLES = Set.of("CHANGE_SUBMITTER", "CHANGE_REVIEWER", "CHANGE_MANAGER");
    private static final TypeReference<List<AssetResponse>> ASSET_LIST_RESPONSE = new TypeReference<>() {
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

    @GetMapping("/{assetKey}")
    public ResponseEntity<ApiResponse<AssetResponse>> get(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @PathVariable String assetKey
    ) {
        accessGuard.requireRole(adminKey, adminRole, ASSET_ROLES);
        return noStore(service.get(assetKey));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<List<AssetResponse>>> upload(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(required = false) String categoryFamilyKey,
            @RequestParam(required = false) String categoryProductTypeKey,
            @RequestParam(required = false) String productSku,
            @RequestParam(required = false) String altText,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) String seoTitle,
            @RequestParam(required = false) String seoDescription
    ) {
        accessGuard.requireRole(adminKey, adminRole, ASSET_ROLES);
        AssetUploadRequest request = new AssetUploadRequest(categoryFamilyKey, categoryProductTypeKey, productSku, altText, tags, seoTitle, seoDescription);
        return noStore(mutations.run(
                "admin-assets:upload",
                idempotencyKey,
                MutationFingerprint.multipart(objectMapper, "POST", "/api/v1/admin/assets", files, uploadFields(request)),
                "admin-assets:upload:" + idempotencyKey,
                ASSET_LIST_RESPONSE,
                () -> service.upload(files, request)
        ));
    }

    @PostMapping(path = "/{assetKey}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AssetResponse>> replaceImage(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String assetKey,
            @RequestPart("file") MultipartFile file
    ) {
        accessGuard.requireRole(adminKey, adminRole, ASSET_ROLES);
        return noStore(mutations.run(
                "admin-assets:replace-image:" + assetKey,
                idempotencyKey,
                MutationFingerprint.multipart(objectMapper, "POST", "/api/v1/admin/assets/" + assetKey + "/image", List.of(file), Map.of("assetKey", assetKey)),
                "admin-assets:asset:" + assetKey,
                ASSET_RESPONSE,
                () -> service.replaceImage(assetKey, file)
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

    private Map<String, Object> uploadFields(AssetUploadRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("categoryFamilyKey", request.categoryFamilyKey());
        fields.put("categoryProductTypeKey", request.categoryProductTypeKey());
        fields.put("productSku", request.productSku());
        fields.put("altText", request.altText());
        fields.put("tags", request.tags());
        fields.put("seoTitle", request.seoTitle());
        fields.put("seoDescription", request.seoDescription());
        return fields;
    }
}
