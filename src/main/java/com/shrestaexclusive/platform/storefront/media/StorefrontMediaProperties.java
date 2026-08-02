package com.shrestaexclusive.platform.storefront.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shresta.media")
public class StorefrontMediaProperties {

    /**
     * Public object-storage origin used to build customer/admin image URLs.
     * Production should normally point this at CloudFront in front of S3.
     */
    private String assetBaseUrl = "";

    private String deliveryMode = "s3-compatible";

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
