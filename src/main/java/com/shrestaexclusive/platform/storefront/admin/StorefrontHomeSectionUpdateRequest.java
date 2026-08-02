package com.shrestaexclusive.platform.storefront.admin;

import com.shrestaexclusive.platform.storefront.home.StorefrontHomeSectionUpdateCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record StorefrontHomeSectionUpdateRequest(
        @Size(max = 160) String eyebrow,
        @Size(max = 180) String title,
        @Size(max = 2000) String description,
        @Min(0) Integer sortOrder,
        Map<String, Object> metadata
) {

    StorefrontHomeSectionUpdateCommand toCommand(String sectionKey) {
        return new StorefrontHomeSectionUpdateCommand(
                sectionKey,
                eyebrow,
                title,
                description,
                sortOrder,
                metadata
        );
    }
}
