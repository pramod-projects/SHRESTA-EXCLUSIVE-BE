package com.shrestaexclusive.platform.storefront.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.shrestaexclusive.platform.testsupport.ImmediateKvReadThroughCache;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.GalleryRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.ItemRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.MediaRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.SectionRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeItemCreateCommand;
import com.shrestaexclusive.platform.storefront.media.StorefrontMediaProperties;
import com.shrestaexclusive.platform.storefront.media.StorefrontMediaUrlBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StorefrontHomeServiceTest {

    @Test
    void assemblesStorefrontHomeFromRepositoryRows() {
        StorefrontMediaProperties mediaProperties = new StorefrontMediaProperties();
        mediaProperties.setAssetBaseUrl("http://localhost:9010/shresta-local-assets");
        StorefrontHomeService service = new StorefrontHomeService(
                new StubRepository(),
                new StorefrontMediaUrlBuilder(mediaProperties),
                new ImmediateKvReadThroughCache()
        );

        StorefrontHomeResponse response = service.getHome();

        assertThat(response.brand().logo().url()).isEqualTo("http://localhost:9010/shresta-local-assets/shresta-logo.png?v=1");
        assertThat(response.navigation()).extracting(StorefrontHomeResponse.NavigationItem::label)
                .containsExactly("Shop");
        assertThat(response.heroSlides()).first()
                .extracting(StorefrontHomeResponse.HeroSlide::familyKey)
                .isEqualTo("silk_saree");
        assertThat(response.featuredCollectionsSection().title()).isEqualTo("Shop by Category");
        assertThat(response.featuredCollections()).first()
                .extracting(StorefrontHomeResponse.FeaturedCollection::familyKey)
                .isEqualTo("silk_saree");
        assertThat(response.featuredCollections().get(0).itemCount()).isEqualTo(2);
        assertThat(response.featuredCollections()).filteredOn(collection -> collection.productBadgeFilters().contains("New"))
                .singleElement()
                .extracting(StorefrontHomeResponse.FeaturedCollection::itemCount)
                .isEqualTo(1);
        assertThat(response.bestsellers()).first()
                .extracting(StorefrontHomeResponse.ProductCard::pricePaise)
                .isEqualTo(1299000L);
        assertThat(response.bestsellers()).first()
                .extracting(StorefrontHomeResponse.ProductCard::description)
                .isEqualTo("Festive silk saree with maroon body and zari border.");
        assertThat(response.bestsellers()).first()
                .extracting(StorefrontHomeResponse.ProductCard::longDescription)
                .isEqualTo("A refined saree selection for weddings, festivals, and family occasions.");
        assertThat(response.materialShowcase().stories()).hasSize(1);
    }

    private static final class StubRepository implements StorefrontHomeRepository {

        @Override
        public List<SectionRow> findActiveSections() {
            return List.of(
                    section("brand", "brand", null, "SHRESTA EXCLUSIVE", "Brand", 10, Map.of()),
                    section("navigation", "navigation", null, "Navigation", "Navigation", 20, Map.of()),
                    section("hero", "hero_carousel", "Hero", "Hero", "Hero", 30, Map.of()),
                    section("trust_badges", "trust_badges", null, "Trust", "Trust", 40, Map.of()),
                    section("featured_collections", "collection_grid", "Categories", "Shop by Category", "Collections", 50, Map.of()),
                    section("why_shresta", "feature_grid", null, "Why Choose SHRESTA?", "Why", 60, Map.of()),
                    section("bestsellers", "product_grid", null, "Bestsellers", "Products", 70, Map.of()),
                    section("material_showcase", "material_showcase", "Materials", "Materials", "Material stories", 80, Map.of()),
                    section("newsletter", "newsletter", "Offer", "Join", "Newsletter", 90, Map.of("ctaLabel", "Subscribe"))
            );
        }

        @Override
        public List<ItemRow> findActiveItems(List<String> sectionKeys) {
            return List.of(
                    item("brand", "brand-shresta-exclusive", null, "SHRESTA EXCLUSIVE", null, "Brand", null, null, 10, true, Map.of(), media("shresta-logo.png")),
                    item("navigation", "nav-shop", null, "Shop", null, null, null, "/products", 10, true, Map.of(), null),
                    item("hero", "hero-occasion-ready", "silk_saree", "Sarees", "SHRESTA EXCLUSIVE", "Hero", "Shop", "/products", 10, true, Map.of("trustNote", "Curated"), media("categories/silk-saree-maroon-gold.png")),
                    item("trust_badges", "trust-quality", null, "Quality Checked", null, "Trust", null, null, 10, true, Map.of("iconKey", "shield"), null),
                    item("featured_collections", "collection-silk-sarees", "silk_saree", "Silk Sarees", null, "Sarees", null, null, 10, true, Map.of("slug", "silk-sarees", "itemCount", 28, "qualityBadges", List.of("Pure Silk")), media("categories/silk-saree-maroon-gold.png")),
                    item("featured_collections", "collection-new-arrivals", "silk_saree", "New Arrivals", null, "Fresh drops", null, null, 20, true, Map.of("slug", "new-arrivals", "itemCount", 36, "productBadgeFilters", List.of("New"), "qualityBadges", List.of("Fresh Drops")), media("categories/silk-saree-maroon-gold.png")),
                    item("why_shresta", "why-catalog", null, "Saree-focused", null, "Why", null, null, 10, true, Map.of("iconKey", "sparkles"), null),
                    item("bestsellers", "product-shresta-silk-0001", "silk_saree", "Maroon Zari Silk Saree", null, "Festive silk saree with maroon body and zari border.", null, null, 10, true, Map.of("sku", "SHRESTA-SILK-0001", "slug", "maroon-zari-silk-saree", "productType", "kanchipuram_saree", "pricePaise", 1299000L, "compareAtPricePaise", 1899000L, "rating", 4.8, "reviewCount", 42, "badges", List.of("Pure Silk"), "longDescription", "A refined saree selection for weddings, festivals, and family occasions."), media("categories/silk-saree-maroon-gold.png")),
                    item("bestsellers", "product-shresta-silk-0008", "silk_saree", "Mehndi Weave Silk Saree", null, null, null, null, 20, true, Map.of("sku", "SHRESTA-SILK-0008", "slug", "mehndi-weave-silk-saree", "productType", "kanchipuram_saree", "pricePaise", 264000L, "compareAtPricePaise", 312000L, "rating", 4.9, "reviewCount", 80, "badges", List.of("New", "Pure Silk")), media("categories/silk-saree-maroon-gold.png")),
                    item("material_showcase", "material-silk-saree", "silk_saree", "Silk & Zari Craft", null, "Materials", null, null, 10, true, Map.of("highlights", List.of("Kanchipuram")), media("categories/silk-saree-maroon-gold.png"))
            );
        }

        @Override
        public void updateSection(StorefrontHomeSectionUpdateCommand command) {
        }

        @Override
        public void updateItem(StorefrontHomeItemUpdateCommand command) {
        }

        @Override
        public void createItem(StorefrontHomeItemCreateCommand command) {
        }

        @Override
        public void updateGallerySlot(String itemKey, int slot, String assetKey) {
        }

        @Override
        public Map<UUID, List<GalleryRow>> findGalleryByItemIds(List<UUID> itemIds) {
            return Map.of();
        }

        private SectionRow section(
                String key,
                String type,
                String eyebrow,
                String title,
                String description,
                int sortOrder,
                Map<String, Object> metadata
        ) {
            return new SectionRow(UUID.randomUUID(), key, type, eyebrow, title, description, sortOrder, metadata);
        }

        private ItemRow item(
                String sectionKey,
                String itemKey,
                String familyKey,
                String title,
                String subtitle,
                String description,
                String ctaLabel,
                String ctaHref,
                int sortOrder,
                boolean featured,
                Map<String, Object> metadata,
                MediaRow media
        ) {
            return new ItemRow(UUID.randomUUID(), sectionKey, itemKey, familyKey, title, subtitle, description, ctaLabel, ctaHref, sortOrder, featured, metadata, media, null);
        }

        private MediaRow media(String assetUrl) {
            return new MediaRow("media", assetUrl, "Alt text", 800, 800, "s3-compatible-local", 1, "data:image/jpeg;base64,abc", List.of());
        }
    }
}
