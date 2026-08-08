package com.shrestaexclusive.platform.order;

import jakarta.validation.constraints.Size;

public record CustomerOrderCancelRequest(
        @Size(max = 240) String note
) {
}
