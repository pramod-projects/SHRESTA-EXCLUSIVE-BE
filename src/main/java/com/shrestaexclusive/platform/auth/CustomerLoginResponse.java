package com.shrestaexclusive.platform.auth;

import java.time.Instant;

public record CustomerLoginResponse(
        String customerId,
        String identityEmail,
        String displayName,
        String authMode,
        Instant issuedAt,
        Instant expiresAt,
        String sessionToken
) {
}
