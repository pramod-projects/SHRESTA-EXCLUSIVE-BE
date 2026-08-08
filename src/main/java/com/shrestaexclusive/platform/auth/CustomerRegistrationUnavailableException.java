package com.shrestaexclusive.platform.auth;

public class CustomerRegistrationUnavailableException extends RuntimeException {

    public CustomerRegistrationUnavailableException() {
        super("Customer account creation is available only in local, dev, and UAT environments.");
    }
}
