package com.shrestaexclusive.platform.storefront.media;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class StorefrontMediaUrlBuilderTest {

    @Test
    void buildsBackendOwnedMediaUrlsFromConfiguredBase() {
        StorefrontMediaProperties properties = new StorefrontMediaProperties();
        properties.setAssetBaseUrl("https://cdn.shresta.example/assets/");
        properties.setDeliveryMode("cloudflare-r2");

        StorefrontMediaUrlBuilder builder = new StorefrontMediaUrlBuilder(properties);

        assertThat(builder.assetUrl("products/shresta-ad--0001.jpg"))
                .isEqualTo("https://cdn.shresta.example/assets/products/shresta-ad--0001.jpg");
        assertThat(builder.deliveryMode()).isEqualTo("cloudflare-r2");
    }

    @Test
    void preservesAbsoluteCdnUrlsStoredInTheDatabase() {
        StorefrontMediaProperties properties = new StorefrontMediaProperties();
        StorefrontMediaUrlBuilder builder = new StorefrontMediaUrlBuilder(properties);

        assertThat(builder.assetUrl("https://images.shresta.example/products/hero.webp"))
                .isEqualTo("https://images.shresta.example/products/hero.webp");
    }
}
