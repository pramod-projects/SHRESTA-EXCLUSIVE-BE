package com.shrestaexclusive.platform.admin.testusers;

import java.time.Instant;

public record AdminTestUserItem(
        String customerId,
        String displayName,
        String email,
        String mobile,
        String note,
        boolean active,
        long testOrdersCount,
        Instant createdAt,
        boolean otpRevealed
) {
}
