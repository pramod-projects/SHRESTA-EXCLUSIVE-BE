package com.shrestaexclusive.platform.auth;

import java.time.Instant;

public record CustomerRegistrationResponse(
        String registrationStatus,
        String customerId,
        String identityEmail,
        String identityMobile,
        String displayName,
        String loginOtp,
        Instant otpExpiresAt,
        String registrationOtp
) {
}
