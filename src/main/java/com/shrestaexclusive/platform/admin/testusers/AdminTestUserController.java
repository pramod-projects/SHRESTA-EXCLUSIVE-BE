package com.shrestaexclusive.platform.admin.testusers;

import java.util.Set;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_KEY_HEADER;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER;

@RestController
@RequestMapping("/api/v1/admin/test-users")
public class AdminTestUserController {

    private static final Set<String> TEST_USER_ROLES = Set.of("CHANGE_SUBMITTER", "CHANGE_APPROVER", "CHANGE_MANAGER", "CHANGE_ADMIN");
    private static final Set<String> TEST_USER_OTP_REVEAL_ROLES = Set.of("CHANGE_MANAGER", "CHANGE_ADMIN");

    private final AdminTestUserService service;
    private final StorefrontAdminAccessGuard accessGuard;

    public AdminTestUserController(AdminTestUserService service, StorefrontAdminAccessGuard accessGuard) {
        this.service = service;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AdminTestUserListResponse>> list(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size
    ) {
        accessGuard.requireRole(adminKey, adminRole, TEST_USER_ROLES);
        return noStore(service.list(page, size));
    }

    @GetMapping("/{customerId}/reveal-otp")
    public ResponseEntity<ApiResponse<AdminTestUserOtpRevealResponse>> revealOtp(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @PathVariable String customerId
    ) {
        accessGuard.requireRole(adminKey, adminRole, TEST_USER_OTP_REVEAL_ROLES);
        return noStore(service.revealOtp(customerId));
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
