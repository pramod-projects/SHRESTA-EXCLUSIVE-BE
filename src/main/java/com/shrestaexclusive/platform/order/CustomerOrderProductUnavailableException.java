package com.shrestaexclusive.platform.order;

public class CustomerOrderProductUnavailableException extends RuntimeException {

    public CustomerOrderProductUnavailableException(String productId) {
        super("Product is unavailable for ordering: " + productId);
    }
}
