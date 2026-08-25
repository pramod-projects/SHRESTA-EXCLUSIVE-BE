package com.shrestaexclusive.platform.email.configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shresta.email")
public class EmailProperties {
    private boolean enabled;
    private String environment = "DEV";
    private String senderEmail = "no-reply@notify.shrestaexclusive.com";
    private String senderName = "SHRESTA";
    private String supportEmail = "support@shrestaexclusive.com";
    private String supportPhoneDisplay = "+91 12345 67890";
    private String supportPhoneDial = "+911234567890";
    private List<String> providerOrder = new ArrayList<>(List.of("BREVO", "RESEND", "MAILJET", "MAILERSEND", "ELASTIC_EMAIL"));
    private List<String> uatAllowlist = new ArrayList<>();
    private String uatRecipientOverride;
    private int maxAttempts = 6;
    private int batchSize = 20;
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(20);
    private Duration baseRetryDelay = Duration.ofSeconds(30);
    private Duration maxRetryDelay = Duration.ofHours(2);
    private Duration terminalRetention = Duration.ofDays(30);
    private Duration webhookRetention = Duration.ofDays(90);
    private Duration unknownOutcomeAlertAge = Duration.ofMinutes(15);
    private long pollDelayMs = 5_000;
    private long webhookReconcileDelayMs = 5_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public String getSupportEmail() { return supportEmail; }
    public void setSupportEmail(String supportEmail) { this.supportEmail = supportEmail; }
    public String getSupportPhoneDisplay() { return supportPhoneDisplay; }
    public void setSupportPhoneDisplay(String supportPhoneDisplay) { this.supportPhoneDisplay = supportPhoneDisplay; }
    public String getSupportPhoneDial() { return supportPhoneDial; }
    public void setSupportPhoneDial(String supportPhoneDial) { this.supportPhoneDial = supportPhoneDial; }
    public List<String> getProviderOrder() { return providerOrder; }
    public void setProviderOrder(List<String> providerOrder) { this.providerOrder = providerOrder; }
    public List<String> getUatAllowlist() { return uatAllowlist; }
    public void setUatAllowlist(List<String> uatAllowlist) { this.uatAllowlist = uatAllowlist; }
    public String getUatRecipientOverride() { return uatRecipientOverride; }
    public void setUatRecipientOverride(String uatRecipientOverride) { this.uatRecipientOverride = uatRecipientOverride; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public Duration getBaseRetryDelay() { return baseRetryDelay; }
    public void setBaseRetryDelay(Duration baseRetryDelay) { this.baseRetryDelay = baseRetryDelay; }
    public Duration getMaxRetryDelay() { return maxRetryDelay; }
    public void setMaxRetryDelay(Duration maxRetryDelay) { this.maxRetryDelay = maxRetryDelay; }
    public Duration getTerminalRetention() { return terminalRetention; }
    public void setTerminalRetention(Duration terminalRetention) { this.terminalRetention = terminalRetention; }
    public Duration getWebhookRetention() { return webhookRetention; }
    public void setWebhookRetention(Duration webhookRetention) { this.webhookRetention = webhookRetention; }
    public Duration getUnknownOutcomeAlertAge() { return unknownOutcomeAlertAge; }
    public void setUnknownOutcomeAlertAge(Duration unknownOutcomeAlertAge) { this.unknownOutcomeAlertAge = unknownOutcomeAlertAge; }
    public long getPollDelayMs() { return pollDelayMs; }
    public void setPollDelayMs(long pollDelayMs) { this.pollDelayMs = pollDelayMs; }
    public long getWebhookReconcileDelayMs() { return webhookReconcileDelayMs; }
    public void setWebhookReconcileDelayMs(long webhookReconcileDelayMs) { this.webhookReconcileDelayMs = webhookReconcileDelayMs; }
}