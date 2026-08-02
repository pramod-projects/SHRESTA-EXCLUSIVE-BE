package com.shrestaexclusive.platform.order;

public class CustomerOrderNotFoundException extends RuntimeException {

    public CustomerOrderNotFoundException(String orderNumber) {
        super("Order was not found: " + orderNumber);
    }
}
