package com.shrestaexclusive.platform.payment.razorpay;

public record RazorpayRefundStatusResponse(
        String refundId,
        String paymentId,
        String status,
        Long amount,
        String currency
) {
}