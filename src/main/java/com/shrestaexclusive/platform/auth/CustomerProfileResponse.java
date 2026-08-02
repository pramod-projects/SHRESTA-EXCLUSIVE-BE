package com.shrestaexclusive.platform.auth;

import java.time.Instant;

public record CustomerProfileResponse(
        String customerId,
        String identityEmail,
        String displayName,
        String status,
        Instant sessionExpiresAt
) {
}
