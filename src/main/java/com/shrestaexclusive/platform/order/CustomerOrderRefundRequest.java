package com.shrestaexclusive.platform.order;

import jakarta.validation.constraints.Size;

public record CustomerOrderRefundRequest(
        @Size(max = 240) String note
) {
}
