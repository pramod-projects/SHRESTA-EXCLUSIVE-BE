package com.shrestaexclusive.platform.email.configuration;

import java.util.List;
import java.util.Set;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shrestaexclusive.platform.common.api.ApiResponse;
import com.shrestaexclusive.platform.email.configuration.NotificationConfigurationService.NotificationConfigurationView;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_KEY_HEADER;
import static com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER;

@RestController
@RequestMapping({
    "/api/v1/admin/configurations/notifications",
    "/api/v1/admin/notification-configuration"
})
public class AdminNotificationConfigurationController {
    private static final Set<String> ROLES = Set.of("CHANGE_SUBMITTER", "CHANGE_APPROVER", "CHANGE_MANAGER", "CHANGE_ADMIN");
    private final NotificationConfigurationService service; private final StorefrontAdminAccessGuard guard;
    public AdminNotificationConfigurationController(NotificationConfigurationService service, StorefrontAdminAccessGuard guard) { this.service = service; this.guard = guard; }

    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationConfigurationView>>> list(
            @RequestHeader(value = ADMIN_KEY_HEADER, required = false) String key,
            @RequestHeader(value = ADMIN_ROLE_HEADER, required = false) String role) {
        guard.requireRole(key, role, ROLES);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.list(), traceId()));
    }
    private String traceId() { String value = MDC.get("traceId"); return value == null ? "not-set" : value; }
}