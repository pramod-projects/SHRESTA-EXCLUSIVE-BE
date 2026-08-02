package com.shrestaexclusive.platform.storefront.home;

public class StorefrontHomeSectionNotFoundException extends RuntimeException {

    public StorefrontHomeSectionNotFoundException(String sectionKey) {
        super("Storefront home section not found: " + sectionKey);
    }
}
