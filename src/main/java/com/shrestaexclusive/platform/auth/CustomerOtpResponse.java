package com.shrestaexclusive.platform.auth;

import java.time.Instant;

public record CustomerOtpResponse(
        String status,
        String destination,
        Instant expiresAt
) {
}
