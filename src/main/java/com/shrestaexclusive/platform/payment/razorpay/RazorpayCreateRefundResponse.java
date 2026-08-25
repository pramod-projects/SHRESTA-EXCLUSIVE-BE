package com.shrestaexclusive.platform.payment.razorpay;

public record RazorpayCreateRefundResponse(
        String refundId,
        String paymentId,
        String status,
        Long amount,
        String currency
) {
}
