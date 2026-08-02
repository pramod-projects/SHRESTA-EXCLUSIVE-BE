package com.shrestaexclusive.platform.storefront.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shresta.admin")
public class StorefrontAdminProperties {

    private String apiKey = "local-shresta-admin-key";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
