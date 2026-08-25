package com.shrestaexclusive.platform.storefront.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shresta.media")
public class StorefrontMediaProperties {

    /**
     * Public object-storage origin used to build customer/admin image URLs.
        * UAT and production point this at the Cloudflare custom media domain.
     */
    private String assetBaseUrl = "";

        private String deliveryMode = "cloudflare-r2";

    public String getAssetBaseUrl() {
        return assetBaseUrl;
    }

    public void setAssetBaseUrl(String assetBaseUrl) {
        this.assetBaseUrl = assetBaseUrl;
    }

    public String getDeliveryMode() {
        return deliveryMode;
    }

    public void setDeliveryMode(String deliveryMode) {
        this.deliveryMode = deliveryMode;
    }
}
