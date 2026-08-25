package com.shrestaexclusive.platform.admin.testusers;

public class TestUserNotFoundException extends RuntimeException {

    public TestUserNotFoundException(String customerId) {
        super("No test user exists for customerId " + customerId);
    }
}
