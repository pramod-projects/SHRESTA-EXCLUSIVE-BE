package com.shrestaexclusive.platform.order;

import java.time.Instant;

public record AdminCustomerOrderSummaryResponse(
        String customerId,
        String customerEmail,
        String customerDisplayName,
        int totalOrders,
        int deliveredOrders,
        int cancelledOrders,
        int activeOrders,
        long grossOrderValuePaise,
        Instant lastOrderAt
) {
}
