package com.shrestaexclusive.platform.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "shresta.sms")
public class CustomerSmsProperties {

    private boolean enabled = false;
    private String springEdgeEndpoint = "https://api.springedge.com/v1/sms/send";
    private String springEdgeApiMode = "json-v1";
    private String springEdgeApiKey = "";
    private String springEdgeSender = "SHRSTA";
    private String springEdgeRoute = "transactional";
    private String springEdgeCountryCode = "91";
    private boolean springEdgeWebhookAuthEnabled = false;
    private String springEdgeWebhookAuthHeader = "x-springedge-webhook-token";
    private String springEdgeWebhookAuthToken = "";
    private boolean springEdgeWebhookSignatureEnabled = false;
    private String springEdgeWebhookSignatureHeader = "x-springedge-signature";
    private String springEdgeWebhookSignatureSecret = "";

    private String msg91Endpoint = "https://control.msg91.com/api/v5/flow";
    private String msg91ApiMode = "flow-v5";
    private String msg91AuthKey = "";
    private String msg91Sender = "SHRSTA";
    private String msg91Route = "4";
    private String msg91Country = "91";
    private String msg91TemplateId = "";
    private String msg91TemplateVariableKey = "OTP";
    private boolean msg91WebhookAuthEnabled = false;
    private String msg91WebhookAuthHeader = "x-msg91-webhook-token";
    private String msg91WebhookAuthToken = "";
    private boolean msg91WebhookSignatureEnabled = false;
    private String msg91WebhookSignatureHeader = "x-msg91-signature";
    private String msg91WebhookSignatureSecret = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSpringEdgeEndpoint() {
        return springEdgeEndpoint;
    }

    public void setSpringEdgeEndpoint(String springEdgeEndpoint) {
        this.springEdgeEndpoint = springEdgeEndpoint;
    }

    public String getSpringEdgeApiKey() {
        return springEdgeApiKey;
    }

    public void setSpringEdgeApiKey(String springEdgeApiKey) {
        this.springEdgeApiKey = springEdgeApiKey;
    }

    public String getSpringEdgeApiMode() {
        return springEdgeApiMode;
    }

    public void setSpringEdgeApiMode(String springEdgeApiMode) {
        this.springEdgeApiMode = springEdgeApiMode;
    }

    public String getSpringEdgeSender() {
        return springEdgeSender;
    }

    public void setSpringEdgeSender(String springEdgeSender) {
        this.springEdgeSender = springEdgeSender;
    }

    public String getSpringEdgeRoute() {
        return springEdgeRoute;
    }

    public void setSpringEdgeRoute(String springEdgeRoute) {
        this.springEdgeRoute = springEdgeRoute;
    }

    public String getSpringEdgeCountryCode() {
        return springEdgeCountryCode;
    }

    public void setSpringEdgeCountryCode(String springEdgeCountryCode) {
        this.springEdgeCountryCode = springEdgeCountryCode;
    }

    public boolean isSpringEdgeWebhookAuthEnabled() {
        return springEdgeWebhookAuthEnabled;
    }

    public void setSpringEdgeWebhookAuthEnabled(boolean springEdgeWebhookAuthEnabled) {
        this.springEdgeWebhookAuthEnabled = springEdgeWebhookAuthEnabled;
    }

    public String getSpringEdgeWebhookAuthHeader() {
        return springEdgeWebhookAuthHeader;
    }

    public void setSpringEdgeWebhookAuthHeader(String springEdgeWebhookAuthHeader) {
        this.springEdgeWebhookAuthHeader = springEdgeWebhookAuthHeader;
    }

    public String getSpringEdgeWebhookAuthToken() {
        return springEdgeWebhookAuthToken;
    }

    public void setSpringEdgeWebhookAuthToken(String springEdgeWebhookAuthToken) {
        this.springEdgeWebhookAuthToken = springEdgeWebhookAuthToken;
    }

    public boolean isSpringEdgeWebhookSignatureEnabled() {
        return springEdgeWebhookSignatureEnabled;
    }

    public void setSpringEdgeWebhookSignatureEnabled(boolean springEdgeWebhookSignatureEnabled) {
        this.springEdgeWebhookSignatureEnabled = springEdgeWebhookSignatureEnabled;
    }

    public String getSpringEdgeWebhookSignatureHeader() {
        return springEdgeWebhookSignatureHeader;
    }

    public void setSpringEdgeWebhookSignatureHeader(String springEdgeWebhookSignatureHeader) {
        this.springEdgeWebhookSignatureHeader = springEdgeWebhookSignatureHeader;
    }

    public String getSpringEdgeWebhookSignatureSecret() {
        return springEdgeWebhookSignatureSecret;
    }

    public void setSpringEdgeWebhookSignatureSecret(String springEdgeWebhookSignatureSecret) {
        this.springEdgeWebhookSignatureSecret = springEdgeWebhookSignatureSecret;
    }

    public String getMsg91Endpoint() {
        return msg91Endpoint;
    }

    public void setMsg91Endpoint(String msg91Endpoint) {
        this.msg91Endpoint = msg91Endpoint;
    }

    public String getMsg91AuthKey() {
        return msg91AuthKey;
    }

    public void setMsg91AuthKey(String msg91AuthKey) {
        this.msg91AuthKey = msg91AuthKey;
    }

    public String getMsg91ApiMode() {
        return msg91ApiMode;
    }

    public void setMsg91ApiMode(String msg91ApiMode) {
        this.msg91ApiMode = msg91ApiMode;
    }

    public String getMsg91Sender() {
        return msg91Sender;
    }

    public void setMsg91Sender(String msg91Sender) {
        this.msg91Sender = msg91Sender;
    }

    public String getMsg91Route() {
        return msg91Route;
    }

    public void setMsg91Route(String msg91Route) {
        this.msg91Route = msg91Route;
    }

    public String getMsg91Country() {
        return msg91Country;
    }

    public void setMsg91Country(String msg91Country) {
        this.msg91Country = msg91Country;
    }

    public String getMsg91TemplateId() {
        return msg91TemplateId;
    }

    public void setMsg91TemplateId(String msg91TemplateId) {
        this.msg91TemplateId = msg91TemplateId;
    }

    public String getMsg91TemplateVariableKey() {
        return msg91TemplateVariableKey;
    }

    public void setMsg91TemplateVariableKey(String msg91TemplateVariableKey) {
        this.msg91TemplateVariableKey = msg91TemplateVariableKey;
    }

    public boolean isMsg91WebhookAuthEnabled() {
        return msg91WebhookAuthEnabled;
    }

    public void setMsg91WebhookAuthEnabled(boolean msg91WebhookAuthEnabled) {
        this.msg91WebhookAuthEnabled = msg91WebhookAuthEnabled;
    }

    public String getMsg91WebhookAuthHeader() {
        return msg91WebhookAuthHeader;
    }

    public void setMsg91WebhookAuthHeader(String msg91WebhookAuthHeader) {
        this.msg91WebhookAuthHeader = msg91WebhookAuthHeader;
    }

    public String getMsg91WebhookAuthToken() {
        return msg91WebhookAuthToken;
    }

    public void setMsg91WebhookAuthToken(String msg91WebhookAuthToken) {
        this.msg91WebhookAuthToken = msg91WebhookAuthToken;
    }

    public boolean isMsg91WebhookSignatureEnabled() {
        return msg91WebhookSignatureEnabled;
    }

    public void setMsg91WebhookSignatureEnabled(boolean msg91WebhookSignatureEnabled) {
        this.msg91WebhookSignatureEnabled = msg91WebhookSignatureEnabled;
    }

    public String getMsg91WebhookSignatureHeader() {
        return msg91WebhookSignatureHeader;
    }

    public void setMsg91WebhookSignatureHeader(String msg91WebhookSignatureHeader) {
        this.msg91WebhookSignatureHeader = msg91WebhookSignatureHeader;
    }

    public String getMsg91WebhookSignatureSecret() {
        return msg91WebhookSignatureSecret;
    }

    public void setMsg91WebhookSignatureSecret(String msg91WebhookSignatureSecret) {
        this.msg91WebhookSignatureSecret = msg91WebhookSignatureSecret;
    }

    public boolean isSpringEdgeJsonMode() {
        return "json-v1".equalsIgnoreCase(trimmed(springEdgeApiMode))
                || "v1-json".equalsIgnoreCase(trimmed(springEdgeApiMode));
    }

    public boolean isMsg91FlowMode() {
        return "flow-v5".equalsIgnoreCase(trimmed(msg91ApiMode));
    }

    public boolean isDeliveryEnabled() {
        return enabled
                || StringUtils.hasText(springEdgeApiKey)
                || StringUtils.hasText(msg91AuthKey);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
