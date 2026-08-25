package com.shrestaexclusive.platform.email.provider.resend;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shresta.email.providers.resend")
public class ResendProperties {
    private boolean enabled; private String endpoint = "https://api.resend.com/emails"; private String apiKey; private String webhookSecret;
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean value) { enabled = value; }
    public String getEndpoint() { return endpoint; } public void setEndpoint(String value) { endpoint = value; }
    public String getApiKey() { return apiKey; } public void setApiKey(String value) { apiKey = value; }
    public String getWebhookSecret() { return webhookSecret; } public void setWebhookSecret(String value) { webhookSecret = value; }
}