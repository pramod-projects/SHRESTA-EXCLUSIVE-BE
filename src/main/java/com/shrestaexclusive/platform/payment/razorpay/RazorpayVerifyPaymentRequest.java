package com.shrestaexclusive.platform.payment.razorpay;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RazorpayVerifyPaymentRequest(
        @NotBlank @Size(max = 120) String razorpayOrderId,
        @NotBlank @Size(max = 120) String razorpayPaymentId,
        @NotBlank @Size(max = 255) String razorpaySignature
) {
}
