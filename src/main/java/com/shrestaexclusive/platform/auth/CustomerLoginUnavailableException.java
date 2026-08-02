package com.shrestaexclusive.platform.auth;

public class CustomerLoginUnavailableException extends RuntimeException {

    public CustomerLoginUnavailableException() {
        super("Seed customer login is available only in local, dev, and UAT environments.");
    }
}
