package com.shrestaexclusive.platform.auth;

import java.time.Instant;
import java.util.UUID;

public record AuthenticatedCustomer(
        UUID customerId,
        String identityEmail,
        String displayName,
        String status,
        Instant sessionExpiresAt
) {
}
