package com.shrestaexclusive.platform.storefront.admin;

import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class StorefrontAdminAccessGuard {

    public static final String ADMIN_KEY_HEADER = "X-SHRESTA-ADMIN-KEY";
    public static final String ADMIN_ROLE_HEADER = "X-SHRESTA-ADMIN-ROLE";

    private final StorefrontAdminProperties properties;

    public StorefrontAdminAccessGuard(StorefrontAdminProperties properties) {
        this.properties = properties;
    }

    public void requireAdminKey(String submittedKey) {
        String expectedKey = properties.getApiKey();
        if (!StringUtils.hasText(expectedKey) || !StringUtils.hasText(submittedKey)
                || !MessageDigest.isEqual(expectedKey.getBytes(), submittedKey.getBytes())) {
            throw new StorefrontAdminUnauthorizedException();
        }
    }

    public void requireRole(String submittedKey, String submittedRole, Set<String> allowedRoles) {
        requireAdminKey(submittedKey);
        String normalizedRole = StringUtils.hasText(submittedRole)
                ? submittedRole.trim().toUpperCase(Locale.ROOT)
                : "";
        if (!allowedRoles.contains(normalizedRole) && !normalizedRole.equals("SUPER_ADMIN")) {
            throw new StorefrontAdminUnauthorizedException();
        }
    }
}
