package com.shrestaexclusive.platform.asset;

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
        java.util.List<String> tags,
        String seoTitle,
        String seoDescription
) {

    public AssetResponse {
        tags = AssetTagRules.normalize(tags);
    }
}
