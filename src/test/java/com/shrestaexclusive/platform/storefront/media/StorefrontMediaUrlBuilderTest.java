package com.shrestaexclusive.platform.storefront.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StorefrontMediaUrlBuilderTest {

    @Test
    void buildsBackendOwnedMediaUrlsFromConfiguredBase() {
        StorefrontMediaProperties properties = new StorefrontMediaProperties();
        properties.setAssetBaseUrl("https://cdn.shresta.example/assets/");
        properties.setDeliveryMode("cloudfront");

        StorefrontMediaUrlBuilder builder = new StorefrontMediaUrlBuilder(properties);

        assertThat(builder.assetUrl("products/shresta-ad--0001.jpg"))
                .isEqualTo("https://cdn.shresta.example/assets/products/shresta-ad--0001.jpg");
        assertThat(builder.assetUrl("products/shresta-ad--0001.jpg", 7))
                .isEqualTo("https://cdn.shresta.example/assets/products/shresta-ad--0001.jpg?v=7");
        assertThat(builder.deliveryMode()).isEqualTo("cloudfront");
    }

    @Test
    void preservesAbsoluteCdnUrlsStoredInTheDatabase() {
        StorefrontMediaProperties properties = new StorefrontMediaProperties();
        StorefrontMediaUrlBuilder builder = new StorefrontMediaUrlBuilder(properties);

        assertThat(builder.assetUrl("https://images.shresta.example/products/hero.webp"))
                .isEqualTo("https://images.shresta.example/products/hero.webp");
        assertThat(builder.assetUrl("https://images.shresta.example/products/hero.webp?format=auto", 3))
                .isEqualTo("https://images.shresta.example/products/hero.webp?format=auto&v=3");
    }
}
