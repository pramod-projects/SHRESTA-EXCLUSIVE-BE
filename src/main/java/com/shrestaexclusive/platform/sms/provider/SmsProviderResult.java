package com.shrestaexclusive.platform.sms.provider;

public record SmsProviderResult(
        boolean success,
        String providerMessageId,
        Integer httpStatus,
        String requestPayload,
        String responsePayload,
        String errorMessage
) {
}
