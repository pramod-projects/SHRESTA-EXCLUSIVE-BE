package com.shrestaexclusive.platform.email.domain;

public record EmailProviderResult(ProviderOutcome outcome, String providerMessageId, Integer httpStatus,
        String failureClass, long latencyMs, long retryAfterMillis) {
    public EmailProviderResult(ProviderOutcome outcome, String providerMessageId, Integer httpStatus,
            String failureClass, long latencyMs) {
        this(outcome, providerMessageId, httpStatus, failureClass, latencyMs, 0L);
    }

    public static EmailProviderResult accepted(String messageId, int status, long latencyMs) {
        return new EmailProviderResult(ProviderOutcome.ACCEPTED, messageId, status, null, latencyMs, 0L);
    }
}