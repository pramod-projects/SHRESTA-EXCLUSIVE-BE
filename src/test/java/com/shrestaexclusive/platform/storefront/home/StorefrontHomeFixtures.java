package com.shrestaexclusive.platform.storefront.home;

import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.Brand;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.FeaturedCollection;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.MaterialShowcase;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.MediaAsset;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.NavigationItem;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.Newsletter;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.SectionCopy;
import java.util.List;

public final class StorefrontHomeFixtures {

    private StorefrontHomeFixtures() {
    }

    public static StorefrontHomeResponse sampleHome() {
        MediaAsset media = new MediaAsset(
                "hero-silk-saree-maroon-gold",
                "http://localhost:9010/shresta-local-assets/categories/silk-saree-maroon-gold.png",
                "Silk saree",
                1154,
                1398,
                "s3-compatible-local",
                1,
                "data:image/jpeg;base64,abc",
                List.of(new StorefrontHomeResponse.MediaVariant(
                        "thumbnail",
                        "jpg",
                        160,
                        160,
                        12000,
                        "http://localhost:9010/shresta-local-assets/variants/hero-silk-saree-maroon-gold/v1/160.jpg"
                ))
        );

        return new StorefrontHomeResponse(
                new Brand("brand-shresta-exclusive", "SHRESTA EXCLUSIVE", "Premium quick commerce", media),
                List.of(new NavigationItem("Shop", "/products")),
                List.of(),
                List.of(),
                new SectionCopy("featured_collections", "Shop by category", "Shop by Category", "Collections"),
                List.of(new FeaturedCollection("collection-silk-sarees", "silk_saree", "silk-sarees", "Silk Sarees", "Pure silk", 28, true, List.of(), List.of("Pure Silk"), media)),
                new SectionCopy("bestsellers", null, "Bestsellers", "Products"),
                List.of(),
                new SectionCopy("why_shresta", null, "Why Choose SHRESTA?", "Why"),
                List.of(),
                new MaterialShowcase("Materials", "Materials", "Stories", List.of()),
                new Newsletter("Offer", "Join", "Newsletter", "Subscribe")
        );
    }
}
