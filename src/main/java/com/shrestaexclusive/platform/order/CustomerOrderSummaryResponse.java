package com.shrestaexclusive.platform.order;

import java.time.Instant;

public record CustomerOrderSummaryResponse(
        String orderNumber,
        String orderStatus,
        String paymentStatus,
        String fulfillmentStatus,
        String customerStageCode,
        String customerStageLabel,
        int customerStageIndex,
        String customerStageMeaning,
        boolean customerStageTerminal,
        String currency,
        long totalPaise,
        String deliveryMode,
        String paymentMethod,
        int itemCount,
        Instant placedAt
) {
}
