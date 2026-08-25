package com.shrestaexclusive.platform.payment.razorpay;

public class RazorpayCheckoutSignatureMismatchException extends RuntimeException {
    public RazorpayCheckoutSignatureMismatchException(String message) {
        super(message);
    }
}
