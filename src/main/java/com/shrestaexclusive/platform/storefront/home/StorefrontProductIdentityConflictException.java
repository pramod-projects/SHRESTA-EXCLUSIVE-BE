package com.shrestaexclusive.platform.storefront.home;

public class StorefrontProductIdentityConflictException extends RuntimeException {

    public StorefrontProductIdentityConflictException() {
        super("Product SKU and slug must be unique");
    }
}
