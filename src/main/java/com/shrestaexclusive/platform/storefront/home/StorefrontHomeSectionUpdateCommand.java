package com.shrestaexclusive.platform.storefront.home;

import java.util.Map;

public record StorefrontHomeSectionUpdateCommand(
        String sectionKey,
        String eyebrow,
        String title,
        String description,
        Integer sortOrder,
        Map<String, Object> metadata
) {
}
