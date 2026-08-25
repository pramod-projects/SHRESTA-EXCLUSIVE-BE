package com.shrestaexclusive.platform.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomerOrderDraftPaymentFailedRequest(
        @NotBlank @Pattern(regexp = "^payment\\.failed$") String eventType,
        @Size(max = 120) String razorpayOrderId,
        @Size(max = 120) String razorpayPaymentId,
        @Size(max = 500) String failureReason
) {
}
