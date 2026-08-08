package com.shrestaexclusive.platform.order;

import java.util.List;
import java.util.Set;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
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
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {

    private static final Set<String> ORDER_READ_ROLES = Set.of("CHANGE_REVIEWER", "CHANGE_MANAGER", "CHANGE_SUBMITTER");
    private static final Set<String> ORDER_UPDATE_ROLES = Set.of("CHANGE_MANAGER");
    private static final TypeReference<CustomerOrderResponse> ORDER_RESPONSE = new TypeReference<>() {
    };

    private final CustomerOrderLifecycleService lifecycleService;
    private final StorefrontAdminAccessGuard accessGuard;
    private final IdempotentMutationCoordinator mutations;
    private final ObjectMapper objectMapper;

    public AdminOrderController(
            CustomerOrderLifecycleService lifecycleService,
            StorefrontAdminAccessGuard accessGuard,
            IdempotentMutationCoordinator mutations,
            ObjectMapper objectMapper
    ) {
        this.lifecycleService = lifecycleService;
        this.accessGuard = accessGuard;
        this.mutations = mutations;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AdminOrderSummaryResponse>>> listOrders(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String customerEmail,
            @RequestParam(required = false) String orderNumber
    ) {
        accessGuard.requireRole(adminKey, adminRole, ORDER_READ_ROLES);
        return noStore(lifecycleService.listOrdersForAdmin(limit, offset, customerEmail, orderNumber));
    }

    @GetMapping("/customers")
    public ResponseEntity<ApiResponse<List<AdminCustomerOrderSummaryResponse>>> listCustomerOrderSummaries(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        accessGuard.requireRole(adminKey, adminRole, ORDER_READ_ROLES);
        return noStore(lifecycleService.listCustomerSummariesForAdmin(limit, offset));
    }

    @GetMapping("/{orderNumber}")
    public ResponseEntity<ApiResponse<CustomerOrderResponse>> findOrder(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @PathVariable String orderNumber
    ) {
        accessGuard.requireRole(adminKey, adminRole, ORDER_READ_ROLES);
        return noStore(lifecycleService.findOrderForAdmin(orderNumber));
    }

    @PatchMapping("/{orderNumber}/status")
    public ResponseEntity<ApiResponse<CustomerOrderResponse>> updateStatuses(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String orderNumber,
            @Valid @RequestBody AdminOrderStatusUpdateRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, ORDER_UPDATE_ROLES);

        CustomerOrderResponse response = mutations.run(
                "admin-orders:update:" + orderNumber,
                idempotencyKey,
                MutationFingerprint.json(objectMapper, "PATCH", "/api/v1/admin/orders/" + orderNumber + "/status", request),
                "admin-orders:" + orderNumber,
                ORDER_RESPONSE,
                () -> lifecycleService.updateOrderStatusesByAdmin(orderNumber, request, adminRole)
        );

        return noStore(response);
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
