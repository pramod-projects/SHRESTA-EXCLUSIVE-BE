package com.shrestaexclusive.platform.storefront.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shresta.admin")
public class StorefrontAdminProperties {

    private String apiKey = "";
    private String bootstrapEmail = "";
    private String bootstrapPassword = "";
    private String bootstrapRole = "SUPER_ADMIN";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBootstrapEmail() {
        return bootstrapEmail;
    }

    public void setBootstrapEmail(String bootstrapEmail) {
        this.bootstrapEmail = bootstrapEmail;
    }

    public String getBootstrapPassword() {
        return bootstrapPassword;
    }

    public void setBootstrapPassword(String bootstrapPassword) {
        this.bootstrapPassword = bootstrapPassword;
    }

    public String getBootstrapRole() {
        return bootstrapRole;
    }

    public void setBootstrapRole(String bootstrapRole) {
        this.bootstrapRole = bootstrapRole;
    }
}
