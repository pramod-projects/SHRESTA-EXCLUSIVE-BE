package com.shrestaexclusive.platform.admin.auth;

import java.time.Instant;

public record AdminUserResponse(
        String email,
        String role,
        boolean active,
        String createdByEmail,
        Instant createdAt,
        Instant updatedAt
) {
}
