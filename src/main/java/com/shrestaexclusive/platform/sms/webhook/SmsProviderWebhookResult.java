package com.shrestaexclusive.platform.sms.webhook;

import java.util.UUID;

public record SmsProviderWebhookResult(
        String provider,
        String providerEventId,
        String providerMessageId,
        String eventType,
        String deliveryStatus,
        UUID linkedSmsMessageId,
        String processingStatus,
        String message
) {
}
