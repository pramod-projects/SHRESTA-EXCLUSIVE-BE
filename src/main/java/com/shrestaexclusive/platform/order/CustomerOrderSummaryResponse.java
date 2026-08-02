package com.shrestaexclusive.platform.order;

import java.time.Instant;

public record CustomerOrderSummaryResponse(
        String orderNumber,
        String orderStatus,
        String paymentStatus,
        String fulfillmentStatus,
        String currency,
        long totalPaise,
        String deliveryMode,
        String paymentMethod,
        int itemCount,
        Instant placedAt
) {
}
