package com.shrestaexclusive.platform.storefront.home;

import com.fasterxml.jackson.core.type.TypeReference;
import com.shrestaexclusive.platform.kv.KvReadThroughCache;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.GalleryRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.ItemRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.MediaRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.SectionRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.Brand;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.FeaturedCollection;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.HeroSlide;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.MaterialShowcase;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.MaterialStory;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.MediaAsset;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.MediaVariant;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.NavigationItem;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.Newsletter;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.ProductCard;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.SectionCopy;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.TrustBadge;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeResponse.WhyShrestaFeature;
import com.shrestaexclusive.platform.storefront.media.StorefrontMediaUrlBuilder;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class StorefrontHomeService {

    public static final List<String> STOREFRONT_HOME_TABLES = List.of(
            "storefront_home_sections",
            "storefront_home_items",
            "storefront_home_item_gallery",
            "media_assets",
            "media_asset_variants"
    );
    private static final TypeReference<StorefrontHomeResponse> STOREFRONT_HOME_RESPONSE = new TypeReference<>() {
    };
    private static final String STOREFRONT_HOME_CACHE_KEY = "active:v9";

    private final StorefrontHomeRepository repository;
    private final StorefrontMediaUrlBuilder media;
    private final KvReadThroughCache kvCache;

    public StorefrontHomeService(StorefrontHomeRepository repository, StorefrontMediaUrlBuilder media, KvReadThroughCache kvCache) {
        this.repository = repository;
        this.media = media;
        this.kvCache = kvCache;
    }

    @Transactional(readOnly = true)
    public StorefrontHomeResponse getHome() {
        return kvCache.getOrLoad("storefront-home", STOREFRONT_HOME_CACHE_KEY, STOREFRONT_HOME_TABLES, STOREFRONT_HOME_RESPONSE, this::loadHomeFromDb);
    }

    @Transactional
    public StorefrontHomeResponse updateSection(StorefrontHomeSectionUpdateCommand command) {
        repository.updateSection(command);
        return refreshHomeKv();
    }

    @Transactional
    public StorefrontHomeResponse updateItem(StorefrontHomeItemUpdateCommand command) {
        repository.updateItem(command);
        return refreshHomeKv();
    }

    @Transactional
    public StorefrontHomeResponse createItem(StorefrontHomeItemCreateCommand command) {
        repository.createItem(command);
        return refreshHomeKv();
    }

    /**
     * Apply a single-slot gallery patch without touching any other slot or field.
     * {@code assetKey} blank / null = clear the slot; non-blank = set it.
     */
    @Transactional
    public StorefrontHomeResponse updateItemGallerySlot(String itemKey, int slot, String assetKey) {
        repository.updateGallerySlot(itemKey, slot, assetKey);
        return refreshHomeKv();
    }

    public StorefrontHomeResponse refreshHomeKv() {
        StorefrontHomeResponse response = loadHomeFromDb();
        publishAfterCommit(response);
        return response;
    }

    private void publishAfterCommit(StorefrontHomeResponse response) {
        Runnable publisher = () -> {
            kvCache.invalidateTables(STOREFRONT_HOME_TABLES);
            kvCache.putFresh("storefront-home", STOREFRONT_HOME_CACHE_KEY, STOREFRONT_HOME_TABLES, response);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.run();
                }
            });
        } else {
            publisher.run();
        }
    }

    private StorefrontHomeResponse loadHomeFromDb() {
        StorefrontDataset dataset = loadDataset();
        return toResponse(dataset);
    }

    private StorefrontDataset loadDataset() {
        List<SectionRow> sections = repository.findActiveSections();
        Map<String, SectionRow> sectionsByKey = sections.stream()
                .collect(Collectors.toMap(SectionRow::sectionKey, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        List<ItemRow> items = repository.findActiveItems(List.copyOf(sectionsByKey.keySet()));
        Map<String, List<ItemRow>> itemsBySection = items.stream()
                .collect(Collectors.groupingBy(ItemRow::sectionKey, LinkedHashMap::new, Collectors.toList()));

        List<UUID> productItemIds = itemsBySection.getOrDefault("bestsellers", List.of())
                .stream().map(ItemRow::id).toList();
        Map<UUID, List<GalleryRow>> galleryByItemId = repository.findGalleryByItemIds(productItemIds);

        return new StorefrontDataset(sectionsByKey, itemsBySection, galleryByItemId);
    }

    private StorefrontHomeResponse toResponse(StorefrontDataset dataset) {
        List<ProductCard> productCards = bestsellers(dataset);
        return new StorefrontHomeResponse(
                brand(dataset),
                navigation(dataset),
                heroSlides(dataset),
                trustBadges(dataset),
                sectionCopy(dataset, "featured_collections"),
                featuredCollections(dataset, productCards),
                sectionCopy(dataset, "bestsellers"),
                productCards,
                sectionCopy(dataset, "why_shresta"),
                whyShresta(dataset),
                materialShowcase(dataset),
                newsletter(dataset)
        );
    }

    private Brand brand(StorefrontDataset dataset) {
        ItemRow item = requiredFirst(dataset, "brand");
        return new Brand(item.itemKey(), item.title(), item.description(), media(item.media()), resolveDemoVideoUrl(item.demoVideoUrl()));
    }

    private List<NavigationItem> navigation(StorefrontDataset dataset) {
        return items(dataset, "navigation").stream()
                .map(item -> new NavigationItem(item.title(), item.ctaHref()))
                .toList();
    }

    private List<HeroSlide> heroSlides(StorefrontDataset dataset) {
        return items(dataset, "hero").stream()
                .map(item -> new HeroSlide(
                        item.itemKey(),
                        item.familyKey(),
                        item.subtitle(),
                        item.title(),
                        item.description(),
                        item.ctaLabel(),
                        item.ctaHref(),
                        string(item.metadata(), "trustNote"),
                        media(item.media())
                ))
                .toList();
    }

    private List<TrustBadge> trustBadges(StorefrontDataset dataset) {
        return items(dataset, "trust_badges").stream()
                .map(item -> new TrustBadge(string(item.metadata(), "iconKey"), item.title(), item.description()))
                .toList();
    }

    private List<FeaturedCollection> featuredCollections(StorefrontDataset dataset, List<ProductCard> productCards) {
        Map<String, Long> productCountByFamily = productCards.stream()
                .collect(Collectors.groupingBy(ProductCard::familyKey, LinkedHashMap::new, Collectors.counting()));
        return items(dataset, "featured_collections").stream()
                .map(item -> new FeaturedCollection(
                        item.itemKey(),
                        item.familyKey(),
                        string(item.metadata(), "slug"),
                        item.title(),
                        item.description(),
                        productCountForCollection(item, productCountByFamily, productCards),
                        item.featured(),
                        stringList(item.metadata(), "productBadgeFilters"),
                        stringList(item.metadata(), "qualityBadges"),
                        media(item.media())
                ))
                .toList();
    }

    private List<ProductCard> bestsellers(StorefrontDataset dataset) {
        return items(dataset, "bestsellers").stream()
                .map(item -> new ProductCard(
                        item.itemKey(),
                        string(item.metadata(), "sku"),
                        item.title(),
                        string(item.metadata(), "slug"),
                        item.description(),
                        string(item.metadata(), "longDescription"),
                        item.familyKey(),
                        string(item.metadata(), "productType"),
                        longValue(item.metadata(), "pricePaise"),
                        longValue(item.metadata(), "compareAtPricePaise"),
                        doubleValue(item.metadata(), "rating"),
                        integer(item.metadata(), "reviewCount"),
                        integer(item.metadata(), "stockQuantity"),
                        stringList(item.metadata(), "badges"),
                        media(item.media()),
                        galleryImages(dataset, item.id()),
                        resolveDemoVideoUrl(item.demoVideoUrl()),
                        item.featured()
                ))
                .toList();
    }

    private String resolveDemoVideoUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return media.assetUrl(value);
    }

    private List<MediaAsset> galleryImages(StorefrontDataset dataset, UUID itemId) {
        List<GalleryRow> rows = dataset.galleryByItemId().getOrDefault(itemId, List.of());
        return rows.stream()
                .sorted(Comparator.comparingInt(GalleryRow::sortOrder))
                .map(row -> media(row.media()))
                .filter(asset -> asset != null)
                .toList();
    }

    private int productCountForCollection(ItemRow collection, Map<String, Long> productCountByFamily, List<ProductCard> productCards) {
        String familyKey = collection.familyKey();
        List<String> productBadgeFilters = stringList(collection.metadata(), "productBadgeFilters");
        if (!productBadgeFilters.isEmpty()) {
            return Math.toIntExact(productCards.stream()
                    .filter(product -> matchesAnyBadgeFilter(product, productBadgeFilters))
                    .count());
        }

        if (familyKey == null || familyKey.isBlank()) {
            return productCards.size();
        }

        return Math.toIntExact(productCountByFamily.getOrDefault(familyKey, 0L));
    }

    private boolean matchesAnyBadgeFilter(ProductCard product, List<String> productBadgeFilters) {
        List<String> normalizedFilters = productBadgeFilters.stream()
                .map(StorefrontHomeService::badgeToken)
                .toList();
        return product.badges().stream()
                .map(StorefrontHomeService::badgeToken)
                .anyMatch(normalizedFilters::contains);
    }

    private static String badgeToken(String badge) {
        return badge.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    private List<WhyShrestaFeature> whyShresta(StorefrontDataset dataset) {
        return items(dataset, "why_shresta").stream()
                .map(item -> new WhyShrestaFeature(string(item.metadata(), "iconKey"), item.title(), item.description()))
                .toList();
    }

    private MaterialShowcase materialShowcase(StorefrontDataset dataset) {
        SectionRow section = requiredSection(dataset, "material_showcase");
        return new MaterialShowcase(
                section.eyebrow(),
                section.title(),
                section.description(),
                items(dataset, "material_showcase").stream()
                        .map(item -> new MaterialStory(
                                item.itemKey(),
                                item.familyKey(),
                                item.title(),
                                item.description(),
                                stringList(item.metadata(), "highlights"),
                                media(item.media())
                        ))
                        .toList()
        );
    }

    private Newsletter newsletter(StorefrontDataset dataset) {
        SectionRow section = requiredSection(dataset, "newsletter");
        return new Newsletter(section.eyebrow(), section.title(), section.description(), string(section.metadata(), "ctaLabel"));
    }

    private SectionCopy sectionCopy(StorefrontDataset dataset, String sectionKey) {
        SectionRow section = requiredSection(dataset, sectionKey);
        return new SectionCopy(section.sectionKey(), section.eyebrow(), section.title(), section.description());
    }

    private MediaAsset media(MediaRow row) {
        if (row == null) {
            return null;
        }

        return new MediaAsset(
                row.assetKey(),
                media.assetUrl(row.assetUrl(), row.version()),
                row.altText(),
                row.widthPx(),
                row.heightPx(),
                row.deliveryMode(),
                row.version(),
                row.lqipDataUrl(),
                row.variants().stream()
                        .map(variant -> new MediaVariant(
                                variant.variantKey(),
                                variant.format(),
                                variant.widthPx(),
                                variant.heightPx(),
                                variant.byteSize(),
                                media.assetUrl(variant.urlPath(), row.version())
                        ))
                        .toList()
        );
    }

    private List<ItemRow> items(StorefrontDataset dataset, String sectionKey) {
        return dataset.itemsBySection().getOrDefault(sectionKey, List.of());
    }

    private ItemRow requiredFirst(StorefrontDataset dataset, String sectionKey) {
        return items(dataset, sectionKey).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing required storefront section item: " + sectionKey));
    }

    private SectionRow requiredSection(StorefrontDataset dataset, String sectionKey) {
        SectionRow section = dataset.sectionsByKey().get(sectionKey);
        if (section == null) {
            throw new IllegalStateException("Missing required storefront section: " + sectionKey);
        }

        return section;
    }

    private String string(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int integer(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private long longValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private double doubleValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0D;
    }

    private List<String> stringList(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }

        return List.of();
    }

    private record StorefrontDataset(
            Map<String, SectionRow> sectionsByKey,
            Map<String, List<ItemRow>> itemsBySection,
            Map<UUID, List<GalleryRow>> galleryByItemId
    ) {
    }
}
