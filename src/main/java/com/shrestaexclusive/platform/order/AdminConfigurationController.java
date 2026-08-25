package com.shrestaexclusive.platform.order;

import java.util.Set;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.order.RefundPolicyConfigurationService.RefundPolicyConfigurationView;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_KEY_HEADER;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER;

@RestController
@RequestMapping("/api/v1/admin/configurations")
public class AdminConfigurationController {
    private static final Set<String> ROLES = Set.of("CHANGE_SUBMITTER", "CHANGE_APPROVER", "CHANGE_MANAGER", "CHANGE_ADMIN");

    private final RefundPolicyConfigurationService refundPolicyService;
    private final StorefrontAdminAccessGuard guard;

    public AdminConfigurationController(RefundPolicyConfigurationService refundPolicyService, StorefrontAdminAccessGuard guard) {
        this.refundPolicyService = refundPolicyService;
        this.guard = guard;
    }

    @GetMapping("/refund-policy")
    public ResponseEntity<ApiResponse<RefundPolicyConfigurationView>> refundPolicy(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String key,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String role
    ) {
        guard.requireRole(key, role, ROLES);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(refundPolicyService.current(), traceId()));
    }

    private String traceId() {
        String value = MDC.get("traceId");
        return value == null ? "not-set" : value;
    }
}