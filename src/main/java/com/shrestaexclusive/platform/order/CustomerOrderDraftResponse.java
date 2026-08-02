package com.shrestaexclusive.platform.order;

import com.shrestaexclusive.platform.order.CustomerOrderResponse.LineItem;
import java.time.Instant;
import java.util.List;

public record CustomerOrderDraftResponse(
        String orderId,
        String orderNumber,
        String customerId,
        String customerEmail,
        String status,
        String cartSignature,
        String currency,
        long subtotalPaise,
        long deliveryPaise,
        long discountPaise,
        long taxPaise,
        long totalPaise,
        String deliveryMode,
        Instant expiresAt,
        Instant createdAt,
        List<LineItem> lines
) {

    public CustomerOrderDraftResponse {
        lines = List.copyOf(lines);
    }
}
