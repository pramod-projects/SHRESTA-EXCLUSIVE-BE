package com.shrestaexclusive.platform.payment.razorpay;

public record RazorpayVerifyPaymentResponse(
        boolean verified,
        String orderId,
        String paymentId
) {
}
