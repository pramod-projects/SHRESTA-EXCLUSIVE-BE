package com.shrestaexclusive.platform.payment.razorpay;

public record RazorpayCreateOrderResponse(
        String orderId,
        long amount,
        String currency,
        boolean simulated
) {

    public RazorpayCreateOrderResponse(String orderId, long amount, String currency) {
        this(orderId, amount, currency, false);
    }
}
