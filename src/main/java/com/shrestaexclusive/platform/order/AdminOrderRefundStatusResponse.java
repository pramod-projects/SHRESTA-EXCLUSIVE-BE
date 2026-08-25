package com.shrestaexclusive.platform.order;

public record AdminOrderRefundStatusResponse(
        String orderNumber,
        String refundId,
        String razorpayStatus,
        boolean refundSuccessful,
        boolean paymentMarkedRefunded,
        String message
) {
}