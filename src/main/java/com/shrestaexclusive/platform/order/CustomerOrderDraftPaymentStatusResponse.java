package com.shrestaexclusive.platform.order;

import java.time.Instant;

public record CustomerOrderDraftPaymentStatusResponse(
        String orderId,
        String orderNumber,
        String draftStatus,
        String paymentStatus,
        String invalidationReason,
        Instant updatedAt
) {
}
