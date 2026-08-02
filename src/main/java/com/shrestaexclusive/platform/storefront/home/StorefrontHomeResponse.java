package com.shrestaexclusive.platform.storefront.home;

import java.util.List;

public record StorefrontHomeResponse(
        Brand brand,
        List<NavigationItem> navigation,
        List<HeroSlide> heroSlides,
        List<TrustBadge> trustBadges,
        SectionCopy featuredCollectionsSection,
        List<FeaturedCollection> featuredCollections,
        SectionCopy bestsellersSection,
        List<ProductCard> bestsellers,
        SectionCopy whyShrestaSection,
        List<WhyShrestaFeature> whyShresta,
        MaterialShowcase materialShowcase,
        Newsletter newsletter
) {

    public StorefrontHomeResponse {
        navigation = List.copyOf(navigation);
        heroSlides = List.copyOf(heroSlides);
        trustBadges = List.copyOf(trustBadges);
        featuredCollections = List.copyOf(featuredCollections);
        bestsellers = List.copyOf(bestsellers);
        whyShresta = List.copyOf(whyShresta);
    }

    public record Brand(
            String itemKey,
            String name,
            String tagline,
            MediaAsset logo
    ) {
    }

    public record NavigationItem(
            String label,
            String href
    ) {
    }

    public record MediaAsset(
            String assetKey,
            String url,
            String altText,
            int width,
            int height,
            String deliveryMode,
            int version,
            String lqipDataUrl,
            List<MediaVariant> variants
    ) {

        public MediaAsset {
            variants = List.copyOf(variants);
        }
    }

    public record MediaVariant(
            String variantKey,
            String format,
            int width,
            int height,
            long byteSize,
            String url
    ) {
    }

    public record SectionCopy(
            String key,
            String eyebrow,
            String title,
            String description
    ) {
    }

    public record HeroSlide(
            String id,
            String familyKey,
            String eyebrow,
            String title,
            String description,
            String ctaLabel,
            String ctaHref,
            String trustNote,
            MediaAsset image
    ) {
    }

    public record TrustBadge(
            String iconKey,
            String title,
            String description
    ) {
    }

    public record FeaturedCollection(
            String id,
            String familyKey,
            String slug,
            String title,
            String description,
            int itemCount,
            boolean featured,
            List<String> productBadgeFilters,
            List<String> qualityBadges,
            MediaAsset image
    ) {

        public FeaturedCollection {
            productBadgeFilters = List.copyOf(productBadgeFilters);
            qualityBadges = List.copyOf(qualityBadges);
        }
    }

    public record ProductCard(
            String id,
            String sku,
            String name,
            String slug,
            String description,
            String longDescription,
            String familyKey,
            String productType,
            long pricePaise,
            long compareAtPricePaise,
            double rating,
            int reviewCount,
            int stockQuantity,
            List<String> badges,
            MediaAsset image,
            List<MediaAsset> galleryImages,
            String demoVideoUrl,
            boolean isBestseller
    ) {

        public ProductCard {
            badges = List.copyOf(badges);
            galleryImages = List.copyOf(galleryImages);
        }
    }

    public record WhyShrestaFeature(
            String iconKey,
            String title,
            String description
    ) {
    }

    public record MaterialShowcase(
            String eyebrow,
            String title,
            String description,
            List<MaterialStory> stories
    ) {

        public MaterialShowcase {
            stories = List.copyOf(stories);
        }
    }

    public record MaterialStory(
            String id,
            String familyKey,
            String title,
            String description,
            List<String> highlights,
            MediaAsset image
    ) {

        public MaterialStory {
            highlights = List.copyOf(highlights);
        }
    }

    public record Newsletter(
            String eyebrow,
            String title,
            String description,
            String ctaLabel
    ) {
    }
}
