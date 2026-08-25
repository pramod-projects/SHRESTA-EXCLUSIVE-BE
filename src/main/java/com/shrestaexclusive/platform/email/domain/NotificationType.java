package com.shrestaexclusive.platform.email.domain;

import java.time.Duration;
import java.util.Set;

public enum NotificationType {
    OTP(Set.of("otp", "expiresMinutes"), Duration.ofMinutes(10), true),
    EMAIL_VERIFICATION(Set.of("verificationUrl", "expiresMinutes"), Duration.ofHours(24), true),
    PASSWORD_RESET(Set.of("resetUrl", "expiresMinutes"), Duration.ofHours(1), true),
    ACCOUNT_SECURITY(Set.of("message"), Duration.ofDays(2), true),
    ORDER_CONFIRMATION(Set.of("customerName", "orderNumber", "total"), Duration.ofDays(2), false),
    ORDER_CANCELLED(Set.of("customerName", "orderNumber"), Duration.ofDays(2), false),
    ORDER_SHIPPED(Set.of("customerName", "orderNumber", "trackingUrl"), Duration.ofDays(7), false),
    ORDER_OUT_FOR_DELIVERY(Set.of("customerName", "orderNumber"), Duration.ofDays(2), false),
    ORDER_DELIVERED(Set.of("customerName", "orderNumber"), Duration.ofDays(2), false),
    PAYMENT_SUCCESS(Set.of("customerName", "orderNumber", "total"), Duration.ofDays(2), false),
    PAYMENT_FAILED(Set.of("customerName", "orderNumber"), Duration.ofDays(2), false),
    REFUND_INITIATED(Set.of("customerName", "orderNumber", "total"), Duration.ofDays(7), false),
    REFUND_COMPLETED(Set.of("customerName", "orderNumber", "total"), Duration.ofDays(7), false);

    private final Set<String> requiredVariables;
    private final Duration timeToLive;
    private final boolean productionLocked;

    NotificationType(Set<String> requiredVariables, Duration timeToLive, boolean productionLocked) {
        this.requiredVariables = requiredVariables;
        this.timeToLive = timeToLive;
        this.productionLocked = productionLocked;
    }

    public Set<String> requiredVariables() { return requiredVariables; }
    public Duration timeToLive() { return timeToLive; }
    public boolean productionLocked() { return productionLocked; }
}