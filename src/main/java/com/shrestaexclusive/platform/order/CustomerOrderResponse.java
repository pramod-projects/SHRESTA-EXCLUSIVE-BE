package com.shrestaexclusive.platform.order;

import java.time.Instant;
import java.util.List;

public record CustomerOrderResponse(
        String orderNumber,
        String customerId,
        String customerEmail,
        String orderStatus,
        String paymentStatus,
        String fulfillmentStatus,
        String currency,
        long subtotalPaise,
        long deliveryPaise,
        long discountPaise,
        long taxPaise,
        long totalPaise,
        String deliveryMode,
        String paymentMethod,
        ContactSnapshot contact,
        ShippingAddressSnapshot shippingAddress,
        List<LineItem> lines,
        List<StatusEvent> statusEvents,
        Instant placedAt
) {

    public CustomerOrderResponse {
        lines = List.copyOf(lines);
        statusEvents = List.copyOf(statusEvents);
    }

    public record ContactSnapshot(
            String email,
            String phone
    ) {
    }

    public record ShippingAddressSnapshot(
            String fullName,
            String phone,
            String addressLine1,
            String addressLine2,
            String landmark,
            String city,
            String state,
            String postalCode,
            String country,
            String addressType
    ) {
    }

    public record LineItem(
            String productId,
            String sku,
            String slug,
            String name,
            String familyKey,
            String productType,
            int quantity,
            long unitPricePaise,
            long lineTotalPaise,
            String mediaAssetKey,
            String mediaUrl,
            String mediaAltText
    ) {
    }

    public record StatusEvent(
            String eventType,
            String fromStatus,
            String toStatus,
            String actorType,
            String note,
            Instant createdAt
    ) {
    }
}
