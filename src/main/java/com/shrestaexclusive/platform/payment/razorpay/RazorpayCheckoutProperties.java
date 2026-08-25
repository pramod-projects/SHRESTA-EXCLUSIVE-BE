package com.shrestaexclusive.platform.payment.razorpay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "shresta.razorpay.checkout")
public class RazorpayCheckoutProperties {

    private boolean enabled = false;
    private String keyId = "";
    private String keySecret = "";
    private String orderEndpoint = "https://api.razorpay.com/v1/orders";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getKeySecret() {
        return keySecret;
    }

    public void setKeySecret(String keySecret) {
        this.keySecret = keySecret;
    }

    public String getOrderEndpoint() {
        return orderEndpoint;
    }

    public void setOrderEndpoint(String orderEndpoint) {
        this.orderEndpoint = orderEndpoint;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(keyId) && StringUtils.hasText(keySecret);
    }

    public boolean isActive() {
        return enabled || isConfigured();
    }
}
