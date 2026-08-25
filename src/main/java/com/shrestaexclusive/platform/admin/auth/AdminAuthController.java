package com.shrestaexclusive.platform.admin.auth;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shrestaexclusive.platform.common.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AdminLoginResponse>> login(@Validated @RequestBody AdminLoginRequest request) {
        try {
            AdminLoginResponse response = adminAuthService.login(request.email(), request.password());
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                    .body(ApiResponse.ok(response, traceId()));
        } catch (AdminAuthUnauthorizedException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                    .body(ApiResponse.failed("ADMIN_LOGIN_FAILED", exception.getMessage(), traceId()));
        }
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "not-set" : traceId;
    }
}
