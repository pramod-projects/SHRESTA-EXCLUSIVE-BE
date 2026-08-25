package com.shrestaexclusive.platform.email.domain;

public interface EmailProvider {
    String code();
    boolean enabled();
    boolean configured();
    boolean webhookConfigured();
    EmailProviderResult send(EmailMessage message);
}