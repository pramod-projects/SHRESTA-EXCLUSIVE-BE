package com.shrestaexclusive.platform.order;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminOrderStatusUpdateRequest(
        @Pattern(regexp = "^(PENDING|PACKING|OUT_FOR_DELIVERY|DELIVERED)$")
        String fulfillmentStatus,

        @Size(max = 240)
        String note,

        @Size(max = 80)
        String opsReference
) {
}
