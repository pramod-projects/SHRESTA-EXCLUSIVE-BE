package com.shrestaexclusive.platform.asset;

public record GeneratedVariant(
        String variantKey,
        String format,
        int widthPx,
        int heightPx,
        long byteSize,
        String storageKey,
        String urlPath,
        String contentType
) {
}
