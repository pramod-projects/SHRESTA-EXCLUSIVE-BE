package com.shrestaexclusive.platform.storefront.home;

public class StorefrontMediaAssignmentException extends RuntimeException {

    public StorefrontMediaAssignmentException() {
        super("A READY product image owned by this product is required");
    }

    public StorefrontMediaAssignmentException(String message) {
        super(message);
    }
}