package com.shrestaexclusive.platform.email.provider.mailjet;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shresta.email.providers.mailjet")
public class MailjetProperties {
    private boolean enabled; private String endpoint = "https://api.mailjet.com/v3.1/send"; private String apiKey; private String secretKey; private String webhookUsername; private String webhookPassword;
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean value) { enabled = value; }
    public String getEndpoint() { return endpoint; } public void setEndpoint(String value) { endpoint = value; }
    public String getApiKey() { return apiKey; } public void setApiKey(String value) { apiKey = value; }
    public String getSecretKey() { return secretKey; } public void setSecretKey(String value) { secretKey = value; }
    public String getWebhookUsername() { return webhookUsername; } public void setWebhookUsername(String value) { webhookUsername = value; }
    public String getWebhookPassword() { return webhookPassword; } public void setWebhookPassword(String value) { webhookPassword = value; }
}