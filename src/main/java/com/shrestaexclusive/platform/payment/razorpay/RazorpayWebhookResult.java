package com.shrestaexclusive.platform.payment.razorpay;

public record RazorpayWebhookResult(
        boolean accepted,
        String status,
        String eventType,
        String eventId,
        String orderNumber,
        String message
) {
}
