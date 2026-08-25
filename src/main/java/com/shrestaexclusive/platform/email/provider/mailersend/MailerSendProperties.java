package com.shrestaexclusive.platform.email.provider.mailersend;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shresta.email.providers.mailer-send")
public class MailerSendProperties {
    private boolean enabled; private String endpoint = "https://api.mailersend.com/v1/email"; private String apiKey; private String webhookSecret;
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean value) { enabled = value; }
    public String getEndpoint() { return endpoint; } public void setEndpoint(String value) { endpoint = value; }
    public String getApiKey() { return apiKey; } public void setApiKey(String value) { apiKey = value; }
    public String getWebhookSecret() { return webhookSecret; } public void setWebhookSecret(String value) { webhookSecret = value; }
}