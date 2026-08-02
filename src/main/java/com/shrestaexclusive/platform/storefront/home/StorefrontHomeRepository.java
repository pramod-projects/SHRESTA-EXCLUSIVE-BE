package com.shrestaexclusive.platform.storefront.home;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface StorefrontHomeRepository {

    List<SectionRow> findActiveSections();

    List<ItemRow> findActiveItems(List<String> sectionKeys);

    Map<UUID, List<GalleryRow>> findGalleryByItemIds(List<UUID> itemIds);

    void updateSection(StorefrontHomeSectionUpdateCommand command);

    void updateItem(StorefrontHomeItemUpdateCommand command);

    /**
     * Update a single gallery slot for a product without touching any other slot.
     * {@code assetKey} blank / null = deactivate the slot; non-blank = set/replace.
     */
    void updateGallerySlot(String itemKey, int slot, String assetKey);

    void createItem(StorefrontHomeItemCreateCommand command);

    record SectionRow(
            UUID id,
            String sectionKey,
            String sectionType,
            String eyebrow,
            String title,
            String description,
            int sortOrder,
            Map<String, Object> metadata
    ) {
    }

    record ItemRow(
            UUID id,
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
            MediaRow media,
            String demoVideoUrl
    ) {
    }

    record GalleryRow(
            UUID itemId,
            int sortOrder,
            MediaRow media
    ) {
    }

    record MediaRow(
            String assetKey,
            String assetUrl,
            String altText,
            int widthPx,
            int heightPx,
            String deliveryMode,
            int version,
            String lqipDataUrl,
            List<VariantRow> variants
    ) {
        public MediaRow {
            variants = List.copyOf(variants);
        }
    }

    record VariantRow(
            String variantKey,
            String format,
            int widthPx,
            int heightPx,
            long byteSize,
            String urlPath
    ) {
    }
}
