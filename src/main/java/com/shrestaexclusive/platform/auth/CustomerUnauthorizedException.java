package com.shrestaexclusive.platform.auth;

public class CustomerUnauthorizedException extends RuntimeException {

    public CustomerUnauthorizedException() {
        super("Customer session is missing, invalid, or expired.");
    }
}
