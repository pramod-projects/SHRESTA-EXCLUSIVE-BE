package com.shrestaexclusive.platform.storefront.media;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class StorefrontMediaUrlBuilder {

    private final StorefrontMediaProperties properties;

    public StorefrontMediaUrlBuilder(StorefrontMediaProperties properties) {
        this.properties = properties;
    }

    public String assetUrl(String assetPath) {
        return assetUrl(assetPath, null);
    }

    public String assetUrl(String assetPath, Integer version) {
        if (!StringUtils.hasText(assetPath)) {
            throw new IllegalArgumentException("assetPath is required");
        }

        String versionedSuffix = version == null ? "" : cacheBustingSuffix(assetPath, version);
        if (assetPath.startsWith("http://") || assetPath.startsWith("https://")) {
            return assetPath + versionedSuffix;
        }

        String normalizedPath = assetPath.startsWith("/") ? assetPath : "/" + assetPath;
        String baseUrl = properties.getAssetBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("shresta.media.asset-base-url is required to build media URLs");
        }

        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return normalizedBase + normalizedPath + versionedSuffix;
    }

    public String deliveryMode() {
        return properties.getDeliveryMode();
    }

    private String cacheBustingSuffix(String assetPath, int version) {
        return assetPath.contains("?") ? "&v=" + version : "?v=" + version;
    }
}
