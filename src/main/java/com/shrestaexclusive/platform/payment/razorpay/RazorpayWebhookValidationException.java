package com.shrestaexclusive.platform.payment.razorpay;

public class RazorpayWebhookValidationException extends RuntimeException {

    public RazorpayWebhookValidationException(String message) {
        super(message);
    }
}
