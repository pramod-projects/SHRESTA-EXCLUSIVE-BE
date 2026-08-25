package com.shrestaexclusive.platform.admin.testusers;

public class TestUserOtpAlreadyRevealedException extends RuntimeException {

    public TestUserOtpAlreadyRevealedException(String customerId) {
        super("OTP for test user " + customerId + " was already revealed");
    }
}
