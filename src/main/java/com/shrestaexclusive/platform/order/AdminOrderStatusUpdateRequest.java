package com.shrestaexclusive.platform.order;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminOrderStatusUpdateRequest(
        @Pattern(regexp = "^(PLACED|PAYMENT_PENDING|CONFIRMED|PACKING|READY_FOR_PICKUP|OUT_FOR_DELIVERY|DELIVERED|CANCELLED|PAYMENT_FAILED)$")
        String orderStatus,

        @Pattern(regexp = "^(PENDING|AUTHORIZED|CAPTURED|FAILED|REFUNDED)$")
        String paymentStatus,

        @Pattern(regexp = "^(PENDING|ALLOCATED|PACKING|READY|SHIPPED|DELIVERED|CANCELLED)$")
        String fulfillmentStatus,

        @Size(max = 240)
        String note
) {
}
