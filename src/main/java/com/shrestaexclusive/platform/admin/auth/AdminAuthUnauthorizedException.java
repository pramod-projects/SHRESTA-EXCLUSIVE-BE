package com.shrestaexclusive.platform.admin.auth;

public class AdminAuthUnauthorizedException extends RuntimeException {

    public AdminAuthUnauthorizedException() {
        super("Invalid admin credentials.");
    }
}
