package com.shrestaexclusive.platform.admin.auth;

import java.util.List;
import java.util.Set;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_KEY_HEADER;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUsersController {

    private static final Set<String> SUPER_ONLY = Set.of("SUPER_ADMIN");

    private final StorefrontAdminAccessGuard accessGuard;
    private final AdminAuthService adminAuthService;

    public AdminUsersController(StorefrontAdminAccessGuard accessGuard, AdminAuthService adminAuthService) {
        this.accessGuard = accessGuard;
        this.adminAuthService = adminAuthService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AdminUserResponse>>> list(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole
    ) {
        accessGuard.requireRole(adminKey, adminRole, SUPER_ONLY);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(ApiResponse.ok(adminAuthService.listAdmins(), traceId()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdminUserResponse>> create(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = "X-SHRESTA-ADMIN-ACTOR", required = false) String actorEmail,
            @Validated @RequestBody AdminUserCreateRequest request
    ) {
        accessGuard.requireRole(adminKey, adminRole, SUPER_ONLY);
        try {
            AdminUserResponse created = adminAuthService.createAdmin(actorEmail, request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                    .body(ApiResponse.ok(created, traceId()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                    .body(ApiResponse.failed("ADMIN_USER_CREATE_FAILED", exception.getMessage(), traceId()));
        }
    }

    @DeleteMapping("/{email}")
    public ResponseEntity<ApiResponse<AdminUserResponse>> delete(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String adminRole,
            @RequestHeader(value = "X-SHRESTA-ADMIN-ACTOR", required = false) String actorEmail,
            @PathVariable("email") String email
    ) {
        accessGuard.requireRole(adminKey, adminRole, SUPER_ONLY);
        try {
            AdminUserResponse deleted = adminAuthService.deleteAdmin(actorEmail, email);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                    .body(ApiResponse.ok(deleted, traceId()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                    .body(ApiResponse.failed("ADMIN_USER_DELETE_FAILED", exception.getMessage(), traceId()));
        }
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
