package com.shrestaexclusive.platform.order;

import static com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator.IDEMPOTENCY_KEY_HEADER;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.auth.AuthenticatedCustomer;
import com.shrestaexclusive.platform.auth.CustomerAuthService;
import com.shrestaexclusive.platform.auth.CustomerUnauthorizedException;
import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.mutation.IdempotentMutationCoordinator;
import com.shrestaexclusive.platform.mutation.MutationFingerprint;
import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/orders")
public class CustomerOrderController {

    private static final TypeReference<CustomerOrderResponse> ORDER_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<CustomerOrderDraftResponse> ORDER_DRAFT_RESPONSE = new TypeReference<>() {
    };

    private final CustomerOrderService orderService;
    private final CustomerAuthService authService;
    private final IdempotentMutationCoordinator mutations;
    private final ObjectMapper objectMapper;

    public CustomerOrderController(
            CustomerOrderService orderService,
            CustomerAuthService authService,
            IdempotentMutationCoordinator mutations,
            ObjectMapper objectMapper
    ) {
        this.orderService = orderService;
        this.authService = authService;
        this.mutations = mutations;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CustomerOrderResponse>> placeOrder(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CustomerOrderPlacementRequest request
    ) {
        AuthenticatedCustomer customer = authService.authenticatedCustomer(bearerToken(authorization));
        CustomerOrderResponse response = mutate(
                "customer-orders:place:" + customer.customerId(),
                idempotencyKey,
                "POST",
                "/api/v1/customer/orders",
                request,
                "customer-orders:place:" + customer.customerId(),
                ORDER_RESPONSE,
                () -> orderService.placeOrder(customer, request)
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(ApiResponse.ok(response, traceId()));
    }

    @PostMapping("/draft")
    public ResponseEntity<ApiResponse<CustomerOrderDraftResponse>> createDraft(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CustomerOrderDraftRequest request
    ) {
        AuthenticatedCustomer customer = authService.authenticatedCustomer(bearerToken(authorization));
        CustomerOrderDraftResponse response = mutate(
                "customer-orders:draft:" + customer.customerId(),
                idempotencyKey,
                "POST",
                "/api/v1/customer/orders/draft",
                request,
                "customer-orders:draft:" + customer.customerId(),
                ORDER_DRAFT_RESPONSE,
                () -> orderService.createOrReuseDraft(customer, request)
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(ApiResponse.ok(response, traceId()));
    }

    @GetMapping("/{orderNumber}")
    public ResponseEntity<ApiResponse<CustomerOrderResponse>> findOrder(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String orderNumber
    ) {
        AuthenticatedCustomer customer = authService.authenticatedCustomer(bearerToken(authorization));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(ApiResponse.ok(orderService.findOrderForCustomer(customer.customerId(), orderNumber), traceId()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CustomerOrderSummaryResponse>>> listOrders(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        AuthenticatedCustomer customer = authService.authenticatedCustomer(bearerToken(authorization));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(ApiResponse.ok(orderService.listOrdersForCustomer(customer.customerId()), traceId()));
    }

    private <T> T mutate(
            String scope,
            String idempotencyKey,
            String method,
            String path,
            Object payload,
            String lockKey,
            TypeReference<T> responseType,
            Supplier<T> mutation
    ) {
        return mutations.run(
                scope,
                idempotencyKey,
                MutationFingerprint.json(objectMapper, method, path, payload),
                lockKey,
                responseType,
                mutation
        );
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new CustomerUnauthorizedException();
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
