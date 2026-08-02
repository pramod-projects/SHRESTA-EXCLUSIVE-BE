package com.shrestaexclusive.platform.asset;

import java.util.List;

public record AssetResponse(
        String assetKey,
        String originalFilename,
        String assetUrl,
        String altText,
        String categoryFamilyKey,
        String categoryProductTypeKey,
        String productSku,
        String status,
        int version,
        int widthPx,
        int heightPx,
        long byteSize,
        String contentType,
        String deliveryMode,
        String lqipDataUrl,
        List<String> tags,
        String seoTitle,
        String seoDescription,
        List<AssetVariantResponse> variants,
        AssetOptimizationStats optimizationStats
) {

    public AssetResponse {
        tags = AssetTagRules.normalize(tags);
        variants = List.copyOf(variants);
    }
}
