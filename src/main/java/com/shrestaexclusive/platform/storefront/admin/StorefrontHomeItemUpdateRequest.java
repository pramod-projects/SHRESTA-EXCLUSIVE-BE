package com.shrestaexclusive.platform.storefront.admin;

import com.shrestaexclusive.platform.storefront.home.StorefrontHomeItemUpdateCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

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
        @Valid MediaUpdateRequest media
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
                media == null ? null : media.assetUrl(),
                media == null ? null : media.altText(),
                media == null ? null : media.widthPx(),
                media == null ? null : media.heightPx(),
                media == null ? null : media.deliveryMode(),
                null,
                null
        );
    }

    public record MediaUpdateRequest(
            @Size(max = 700) String assetUrl,
            @Size(max = 240) String altText,
            @Min(1) Integer widthPx,
            @Min(1) Integer heightPx,
            @Size(max = 40) String deliveryMode
    ) {
    }
}
