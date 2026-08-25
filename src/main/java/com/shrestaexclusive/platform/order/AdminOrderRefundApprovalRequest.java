package com.shrestaexclusive.platform.order;

import jakarta.validation.constraints.Size;

public record AdminOrderRefundApprovalRequest(
        @Size(max = 240) String note,
        @Size(max = 80) String opsReference
) {
}
