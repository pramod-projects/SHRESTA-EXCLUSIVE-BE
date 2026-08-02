package com.shrestaexclusive.platform.auth;

public class CustomerLoginFailedException extends RuntimeException {

    public CustomerLoginFailedException() {
        super("Invalid email or OTP.");
    }
}
