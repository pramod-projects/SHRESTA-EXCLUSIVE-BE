package com.shrestaexclusive.platform.storefront.home;

import java.util.List;
import java.util.Map;

/**
 * Command to create a new storefront home item (product) in a given section.
 * <p>
 * {@code sectionKey} must match an existing active section (e.g. "bestsellers").
 * {@code itemKey} must be unique across all items and match {@code ^[a-z][a-z0-9_-]*$}.
 * {@code mediaAssetKey} is the asset_key of an already-uploaded media asset, or null for no image.
 */
public record StorefrontHomeItemCreateCommand(
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
        String mediaAssetKey,
        List<String> galleryAssetKeys,
        String demoVideoUrl
) {
}
