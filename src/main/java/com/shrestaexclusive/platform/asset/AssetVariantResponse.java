package com.shrestaexclusive.platform.asset;

public record AssetVariantResponse(
        String variantKey,
        String format,
        int widthPx,
        int heightPx,
        long byteSize,
        String url
) {
}
