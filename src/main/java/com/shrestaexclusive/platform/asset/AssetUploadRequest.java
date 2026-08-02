package com.shrestaexclusive.platform.asset;

import java.util.List;

public record AssetUploadRequest(
        String categoryFamilyKey,
        String categoryProductTypeKey,
        String productSku,
        String altText,
        List<String> tags,
        String seoTitle,
        String seoDescription
) {
    public AssetUploadRequest {
        tags = AssetTagRules.normalize(tags);
    }
}
