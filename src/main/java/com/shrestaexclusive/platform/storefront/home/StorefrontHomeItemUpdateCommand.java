package com.shrestaexclusive.platform.storefront.home;

import java.util.List;
import java.util.Map;

/**
 * Command to update a single storefront home item (product).
 * <p>
 * Gallery slots: {@code galleryAssetKeys} holds up to 4 entries indexed 0-3 (sort_order 1-4).
 * A null or blank entry clears that slot. A null list skips all gallery updates.
 * <p>
 * Demo video: {@code demoVideoUrl} null = skip update, blank = clear the URL, non-blank = set URL.
 */
public record StorefrontHomeItemUpdateCommand(
        String itemKey,
        String familyKey,
        String title,
        String subtitle,
        String description,
        String ctaLabel,
        String ctaHref,
        Integer sortOrder,
        Boolean featured,
        Map<String, Object> metadata,
        String mediaUrl,
        String mediaAltText,
        Integer mediaWidthPx,
        Integer mediaHeightPx,
        String mediaDeliveryMode,
        List<String> galleryAssetKeys,
        String demoVideoUrl
) {
}
