package com.shrestaexclusive.platform.category.admin;

import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_KEY_HEADER;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER;
import static com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.category.config.CategoryFamilyResponse;
import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator;
import com.shrestaexclusive.platform.mutation.MutationFingerprint;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/catalog/categories")
public class AdminCategoryController {

    private static final Set<String> CATEGORY_ROLES = Set.of("CHANGE_SUBMITTER", "CHANGE_REVIEWER", "CHANGE_MANAGER");
    private static final String CATEGORY_LOCK_KEY = "admin-categories:configuration";
    private static final TypeReference<List<CategoryFamilyResponse>> CATEGORY_RESPONSE = new TypeReference<>() {
    };

    private final AdminCategoryService service;
    private final StorefrontAdminAccessGuard accessGuard;
    private final IdempotentMutationCoordinator mutations;
    private final ObjectMapper objectMapper;

    public AdminCategoryController(
            AdminCategoryService service,
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
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> list(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return noStore(service.list());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> createFamily(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CategoryFamilyMutationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:create-family", idempotencyKey, "POST", "/api/v1/admin/catalog/categories", request, () -> service.createFamily(request));
    }

    @PatchMapping("/{familyKey}")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> updateFamily(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @Valid @RequestBody CategoryFamilyMutationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:update-family:" + familyKey, idempotencyKey, "PATCH", "/api/v1/admin/catalog/categories/" + familyKey, request, () -> service.updateFamily(familyKey, request));
    }

    @DeleteMapping("/{familyKey}")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> archiveFamily(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:archive-family:" + familyKey, idempotencyKey, "DELETE", "/api/v1/admin/catalog/categories/" + familyKey, Map.of("familyKey", familyKey), () -> service.archiveFamily(familyKey));
    }

    @PostMapping("/{familyKey}/subcategories")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> createProductType(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @Valid @RequestBody CategoryProductTypeMutationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:create-product-type:" + familyKey, idempotencyKey, "POST", "/api/v1/admin/catalog/categories/" + familyKey + "/subcategories", request, () -> service.createProductType(familyKey, request));
    }

    @PatchMapping("/{familyKey}/subcategories/{typeKey}")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> updateProductType(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @PathVariable String typeKey,
            @Valid @RequestBody CategoryProductTypeMutationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:update-product-type:" + familyKey + ":" + typeKey, idempotencyKey, "PATCH", "/api/v1/admin/catalog/categories/" + familyKey + "/subcategories/" + typeKey, request, () -> service.updateProductType(familyKey, typeKey, request));
    }

    @DeleteMapping("/{familyKey}/subcategories/{typeKey}")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> archiveProductType(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @PathVariable String typeKey
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:archive-product-type:" + familyKey + ":" + typeKey, idempotencyKey, "DELETE", "/api/v1/admin/catalog/categories/" + familyKey + "/subcategories/" + typeKey, Map.of("familyKey", familyKey, "typeKey", typeKey), () -> service.archiveProductType(familyKey, typeKey));
    }

    @PostMapping("/{familyKey}/attributes")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> createAttribute(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @Valid @RequestBody CategoryAttributeMutationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:create-attribute:" + familyKey, idempotencyKey, "POST", "/api/v1/admin/catalog/categories/" + familyKey + "/attributes", request, () -> service.createAttribute(familyKey, request));
    }

    @PatchMapping("/{familyKey}/attributes/{attributeKey}")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> updateAttribute(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @PathVariable String attributeKey,
            @Valid @RequestBody CategoryAttributeMutationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:update-attribute:" + familyKey + ":" + attributeKey, idempotencyKey, "PATCH", "/api/v1/admin/catalog/categories/" + familyKey + "/attributes/" + attributeKey, request, () -> service.updateAttribute(familyKey, attributeKey, request));
    }

    @DeleteMapping("/{familyKey}/attributes/{attributeKey}")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> archiveAttribute(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @PathVariable String attributeKey
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:archive-attribute:" + familyKey + ":" + attributeKey, idempotencyKey, "DELETE", "/api/v1/admin/catalog/categories/" + familyKey + "/attributes/" + attributeKey, Map.of("familyKey", familyKey, "attributeKey", attributeKey), () -> service.archiveAttribute(familyKey, attributeKey));
    }

    @PostMapping("/{familyKey}/filters")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> createFilter(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @Valid @RequestBody CategoryFilterMutationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:create-filter:" + familyKey, idempotencyKey, "POST", "/api/v1/admin/catalog/categories/" + familyKey + "/filters", request, () -> service.createFilter(familyKey, request));
    }

    @PatchMapping("/{familyKey}/filters/{filterKey}")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> updateFilter(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @PathVariable String filterKey,
            @Valid @RequestBody CategoryFilterMutationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:update-filter:" + familyKey + ":" + filterKey, idempotencyKey, "PATCH", "/api/v1/admin/catalog/categories/" + familyKey + "/filters/" + filterKey, request, () -> service.updateFilter(familyKey, filterKey, request));
    }

    @DeleteMapping("/{familyKey}/filters/{filterKey}")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> archiveFilter(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @PathVariable String filterKey
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:archive-filter:" + familyKey + ":" + filterKey, idempotencyKey, "DELETE", "/api/v1/admin/catalog/categories/" + familyKey + "/filters/" + filterKey, Map.of("familyKey", familyKey, "filterKey", filterKey), () -> service.archiveFilter(familyKey, filterKey));
    }

    @PostMapping("/{familyKey}/taxes")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> createTax(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @Valid @RequestBody CategoryTaxMutationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:create-tax:" + familyKey, idempotencyKey, "POST", "/api/v1/admin/catalog/categories/" + familyKey + "/taxes", request, () -> service.createTax(familyKey, request));
    }

    @PatchMapping("/{familyKey}/taxes/{hsnCode}/{effectiveFrom}")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> updateTax(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @PathVariable String hsnCode,
            @PathVariable LocalDate effectiveFrom,
            @Valid @RequestBody CategoryTaxMutationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:update-tax:" + familyKey + ":" + hsnCode + ":" + effectiveFrom, idempotencyKey, "PATCH", "/api/v1/admin/catalog/categories/" + familyKey + "/taxes/" + hsnCode + "/" + effectiveFrom, request, () -> service.updateTax(familyKey, hsnCode, effectiveFrom, request));
    }

    @DeleteMapping("/{familyKey}/taxes/{hsnCode}/{effectiveFrom}")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> archiveTax(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @PathVariable String hsnCode,
            @PathVariable LocalDate effectiveFrom
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:archive-tax:" + familyKey + ":" + hsnCode + ":" + effectiveFrom, idempotencyKey, "DELETE", "/api/v1/admin/catalog/categories/" + familyKey + "/taxes/" + hsnCode + "/" + effectiveFrom, Map.of("familyKey", familyKey, "hsnCode", hsnCode, "effectiveFrom", effectiveFrom), () -> service.archiveTax(familyKey, hsnCode, effectiveFrom));
    }

    @PostMapping("/{familyKey}/styling")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> createStyling(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @Valid @RequestBody CategoryStylingMutationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:create-styling:" + familyKey, idempotencyKey, "POST", "/api/v1/admin/catalog/categories/" + familyKey + "/styling", request, () -> service.createStyling(familyKey, request));
    }

    @PatchMapping("/{familyKey}/styling/{occasionKey}")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> updateStyling(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @PathVariable String occasionKey,
            @Valid @RequestBody CategoryStylingMutationRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:update-styling:" + familyKey + ":" + occasionKey, idempotencyKey, "PATCH", "/api/v1/admin/catalog/categories/" + familyKey + "/styling/" + occasionKey, request, () -> service.updateStyling(familyKey, occasionKey, request));
    }

    @DeleteMapping("/{familyKey}/styling/{occasionKey}")
    public ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> archiveStyling(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String familyKey,
            @PathVariable String occasionKey
    ) {
        accessGuard.requireRole(adminKey, adminRole, CATEGORY_ROLES);
        return mutate("admin-categories:archive-styling:" + familyKey + ":" + occasionKey, idempotencyKey, "DELETE", "/api/v1/admin/catalog/categories/" + familyKey + "/styling/" + occasionKey, Map.of("familyKey", familyKey, "occasionKey", occasionKey), () -> service.archiveStyling(familyKey, occasionKey));
    }

    private ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> mutate(
            String scope,
            String idempotencyKey,
            String method,
            String path,
            Object payload,
            Supplier<List<CategoryFamilyResponse>> mutation
    ) {
        return noStore(mutations.run(
                scope,
                idempotencyKey,
                MutationFingerprint.json(objectMapper, method, path, payload),
                CATEGORY_LOCK_KEY,
                CATEGORY_RESPONSE,
                mutation
        ));
    }

    private ResponseEntity<ApiResponse<List<CategoryFamilyResponse>>> noStore(List<CategoryFamilyResponse> data) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(ApiResponse.ok(data, traceId()));
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
