package com.shrestaexclusive.platform.storefront.home;

import java.util.List;
import java.util.Map;

/**
 * Command to update a single storefront home item (product).
 * <p>
 * Gallery slots: {@code galleryAssetKeys} holds up to 4 entries indexed 0-3 (sort_order 1-4).
 * A null or blank entry clears that slot. A null list skips all gallery updates.
 * <p>
 * Product video: {@code demoVideoAssetKey} null = skip update, blank = clear, non-blank = link a READY owned asset.
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
        String mediaAssetKey,
        List<String> galleryAssetKeys,
        String demoVideoAssetKey
) {
}
