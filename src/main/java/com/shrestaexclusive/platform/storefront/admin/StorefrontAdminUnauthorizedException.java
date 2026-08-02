package com.shrestaexclusive.platform.storefront.admin;

public class StorefrontAdminUnauthorizedException extends RuntimeException {

    public StorefrontAdminUnauthorizedException() {
        super("A valid SHRESTA admin API key is required");
    }
}
