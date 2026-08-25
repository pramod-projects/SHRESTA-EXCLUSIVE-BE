package com.shrestaexclusive.platform.payment.razorpay;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RazorpayCreateOrderRequest(
        @Min(100) long amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @NotBlank @Size(max = 80) String receipt
) {
}
