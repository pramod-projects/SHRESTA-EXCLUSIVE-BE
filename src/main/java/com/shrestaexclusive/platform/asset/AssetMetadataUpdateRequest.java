package com.shrestaexclusive.platform.asset;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AssetMetadataUpdateRequest(
        @Size(max = 240) String altText,
        @Size(max = 64) String categoryFamilyKey,
        @Size(max = 80) String categoryProductTypeKey,
        @Size(max = 80) String productSku,
        @Size(max = AssetTagRules.MAX_TAG_COUNT) List<@Size(max = AssetTagRules.MAX_TAG_LENGTH) @Pattern(regexp = AssetTagRules.TAG_PATTERN_SOURCE) String> tags,
        @Size(max = 180) String seoTitle,
        @Size(max = 300) String seoDescription,
        Boolean clearCategoryFamilyKey,
        Boolean clearCategoryProductTypeKey,
        Boolean clearProductSku,
        Boolean clearTags,
        Boolean clearSeoTitle,
        Boolean clearSeoDescription
) {
    public AssetMetadataUpdateRequest {
        tags = AssetTagRules.normalizeNullable(tags);
    }
}
