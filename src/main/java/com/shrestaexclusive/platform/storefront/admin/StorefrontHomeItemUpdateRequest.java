package com.shrestaexclusive.platform.storefront.admin;

import java.util.Map;

import com.shrestaexclusive.platform.storefront.home.StorefrontHomeItemUpdateCommand;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StorefrontHomeItemUpdateRequest(
        @Pattern(regexp = "^[a-z][a-z0-9_]*$") String familyKey,
        @Size(max = 180) String title,
        @Size(max = 220) String subtitle,
        @Size(max = 2000) String description,
        @Size(max = 80) String ctaLabel,
        @Size(max = 240) String ctaHref,
        @Min(0) Integer sortOrder,
        Boolean featured,
        Map<String, Object> metadata,
        @Size(max = 120) String mediaAssetKey
) {

    StorefrontHomeItemUpdateCommand toCommand(String itemKey) {
        return new StorefrontHomeItemUpdateCommand(
                itemKey,
                familyKey,
                title,
                subtitle,
                description,
                ctaLabel,
                ctaHref,
                sortOrder,
                featured,
                metadata,
                mediaAssetKey,
                null,
                null
        );
    }
}
