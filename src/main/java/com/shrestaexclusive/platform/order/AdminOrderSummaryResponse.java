package com.shrestaexclusive.platform.order;

import java.time.Instant;

public record AdminOrderSummaryResponse(
        String orderNumber,
        String customerId,
        String customerEmail,
        String customerDisplayName,
        String orderStatus,
        String paymentStatus,
        String fulfillmentStatus,
        String deliveryMode,
        String paymentMethod,
        long totalPaise,
        int itemCount,
        Instant placedAt
) {
}
