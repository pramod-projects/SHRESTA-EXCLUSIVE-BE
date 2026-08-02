package com.shrestaexclusive.platform.admin.changes;

import static com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_KEY_HEADER;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator;
import com.shrestaexclusive.platform.mutation.MutationFingerprint;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/change-requests")
public class AdminChangeRequestController {

    private static final Set<String> SUBMIT_ROLES = Set.of("CHANGE_SUBMITTER", "CHANGE_MANAGER");
    private static final Set<String> REVIEW_ROLES = Set.of("CHANGE_REVIEWER", "CHANGE_MANAGER");
    private static final TypeReference<AdminChangeRequestResponse> CHANGE_REQUEST_RESPONSE = new TypeReference<>() {
    };

    private final AdminChangeRequestService service;
    private final StorefrontAdminAccessGuard accessGuard;
    private final IdempotentMutationCoordinator mutations;
    private final ObjectMapper objectMapper;

    public AdminChangeRequestController(
            AdminChangeRequestService service,
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
    public ResponseEntity<ApiResponse<List<AdminChangeRequestResponse>>> list(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestParam(required = false) String status
    ) {
        accessGuard.requireRole(adminKey, adminRole, REVIEW_ROLES);
        return noStore(service.list(status));
    }

    @GetMapping("/{requestKey}")
    public ResponseEntity<ApiResponse<AdminChangeRequestResponse>> get(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @PathVariable String requestKey
    ) {
        accessGuard.requireRole(adminKey, adminRole, REVIEW_ROLES);
        return noStore(service.get(requestKey));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdminChangeRequestResponse>> create(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody AdminChangeRequestCreateRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, SUBMIT_ROLES);
        String role = normalizeRole(adminRole);
        return noStore(mutations.run(
                "admin-change-requests:create",
                idempotencyKey,
                MutationFingerprint.json(objectMapper, "POST", "/api/v1/admin/change-requests", request),
                "admin-change-requests:create:" + idempotencyKey,
                CHANGE_REQUEST_RESPONSE,
                () -> service.create(role, request)
        ));
    }

    /**
     * Upsert: update the payload of any existing PENDING_REVIEW request for the same
     * (entity_key, request_type), or create a new request if none exists.
     * This prevents duplicate pending requests when the admin submits the same form twice.
     */
    @PostMapping("/upsert")
    public ResponseEntity<ApiResponse<AdminChangeRequestResponse>> upsert(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody AdminChangeRequestCreateRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, SUBMIT_ROLES);
        String role = normalizeRole(adminRole);
        return noStore(mutations.run(
                "admin-change-requests:upsert:" + request.entityKey() + ":" + request.requestType(),
                idempotencyKey,
                MutationFingerprint.json(objectMapper, "POST", "/api/v1/admin/change-requests/upsert", request),
                "admin-change-requests:upsert:" + request.entityKey() + ":" + request.requestType(),
                CHANGE_REQUEST_RESPONSE,
                () -> service.createOrUpdatePending(role, request)
        ));
    }

    @PostMapping("/{requestKey}/approve")
    public ResponseEntity<ApiResponse<AdminChangeRequestResponse>> approve(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String requestKey,
            @Valid @RequestBody AdminChangeRequestDecisionRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, REVIEW_ROLES);
        return decide(requestKey, adminRole, idempotencyKey, "approve", request, () -> service.approve(requestKey, normalizeRole(adminRole), request));
    }

    @PostMapping("/{requestKey}/reject")
    public ResponseEntity<ApiResponse<AdminChangeRequestResponse>> reject(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String requestKey,
            @Valid @RequestBody AdminChangeRequestDecisionRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, REVIEW_ROLES);
        return decide(requestKey, adminRole, idempotencyKey, "reject", request, () -> service.reject(requestKey, normalizeRole(adminRole), request));
    }

    private ResponseEntity<ApiResponse<AdminChangeRequestResponse>> decide(
            String requestKey,
            String adminRole,
            String idempotencyKey,
            String decision,
            AdminChangeRequestDecisionRequest request,
            java.util.function.Supplier<AdminChangeRequestResponse> supplier
    ) {
        return noStore(mutations.run(
                "admin-change-requests:" + decision + ":" + requestKey,
                idempotencyKey,
                MutationFingerprint.json(objectMapper, "POST", "/api/v1/admin/change-requests/" + requestKey + "/" + decision, Map.of("request", request, "role", normalizeRole(adminRole))),
                "admin-change-requests:review:" + requestKey,
                CHANGE_REQUEST_RESPONSE,
                supplier
        ));
    }

    private <T> ResponseEntity<ApiResponse<T>> noStore(T data) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(ApiResponse.ok(data, traceId()));
    }

    private String normalizeRole(String role) {
        return StringUtils.hasText(role) ? role.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
