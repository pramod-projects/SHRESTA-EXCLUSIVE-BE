package com.shrestaexclusive.platform.storefront.home;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface StorefrontHomeRepository {

    List<SectionRow> findActiveSections();

    List<ItemRow> findActiveItems(List<String> sectionKeys);

        default List<ItemRow> findAdminItems(List<String> sectionKeys) {
                return findActiveItems(sectionKeys);
        }

    Map<UUID, List<GalleryRow>> findGalleryByItemIds(List<UUID> itemIds);

    void updateSection(StorefrontHomeSectionUpdateCommand command);

    void updateItem(StorefrontHomeItemUpdateCommand command);

        default String findItemImageAssetKeyForUpdate(String itemKey) {
                return null;
        }

        default String findItemVideoAssetKeyForUpdate(String itemKey) {
                return null;
        }

        default String findGalleryAssetKeyForUpdate(String itemKey, int slot) {
                return null;
        }

        default void updateDisplayMedia(String itemKey, String imageAssetKey, String videoAssetKey) {
                throw new UnsupportedOperationException("Display media updates are not supported");
        }

    /**
     * Update a single gallery slot for a product without touching any other slot.
     * {@code assetKey} blank / null = deactivate the slot; non-blank = set/replace.
     */
    void updateGallerySlot(String itemKey, int slot, String assetKey);

    void createItem(StorefrontHomeItemCreateCommand command);

        default void assertUniqueProductIdentity(String itemKey, String sku, String slug) {
        }

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
            List<String> tags
    ) {
                MediaRow(String assetKey, String assetUrl, String altText, int widthPx, int heightPx,
                                String deliveryMode, int version) {
                        this(assetKey, assetUrl, altText, widthPx, heightPx, deliveryMode, version, List.of());
                }
    }
}
