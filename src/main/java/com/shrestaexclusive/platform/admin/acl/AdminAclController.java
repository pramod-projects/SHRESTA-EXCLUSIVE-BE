package com.shrestaexclusive.platform.admin.acl;

import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_KEY_HEADER;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/acl")
public class AdminAclController {

    private static final Set<String> KNOWN_ROLES = Set.of("SUPER_ADMIN", "CHANGE_SUBMITTER", "CHANGE_REVIEWER", "CHANGE_MANAGER");
    private static final Map<String, List<String>> PERMISSIONS_BY_ROLE = Map.of(
            "CHANGE_SUBMITTER", List.of("admin:read", "change_request:submit"),
            "CHANGE_REVIEWER", List.of("admin:read", "change_request:read", "change_request:approve", "change_request:reject"),
            "CHANGE_MANAGER", List.of("admin:read", "change_request:submit", "change_request:read", "change_request:approve", "change_request:reject"),
            "SUPER_ADMIN", List.of("admin:*")
    );

    private final StorefrontAdminAccessGuard accessGuard;

    public AdminAclController(StorefrontAdminAccessGuard accessGuard) {
        this.accessGuard = accessGuard;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AdminAclResponse>> currentAcl(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole
    ) {
        String role = normalizeRole(adminRole);
        accessGuard.requireRole(adminKey, role, KNOWN_ROLES);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(ApiResponse.ok(new AdminAclResponse(role, PERMISSIONS_BY_ROLE.getOrDefault(role, List.of())), traceId()));
    }

    private String normalizeRole(String role) {
        return StringUtils.hasText(role) ? role.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
