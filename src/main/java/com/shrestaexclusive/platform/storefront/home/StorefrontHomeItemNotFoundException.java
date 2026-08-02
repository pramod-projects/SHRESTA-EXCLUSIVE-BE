package com.shrestaexclusive.platform.storefront.home;

public class StorefrontHomeItemNotFoundException extends RuntimeException {

    public StorefrontHomeItemNotFoundException(String itemKey) {
        super("Storefront home item not found: " + itemKey);
    }
}
