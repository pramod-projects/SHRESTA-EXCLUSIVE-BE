package com.shrestaexclusive.platform.sms.provider;

public interface SmsProviderClient {

    String providerCode();

    boolean isConfigured();

    SmsProviderResult send(String mobile, String messageBody);
}
