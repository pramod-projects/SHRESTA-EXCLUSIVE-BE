package com.shrestaexclusive.platform.asset;

public record StoredAsset(
        String assetKey,
        String originalFilename,
        String storageKey,
        String assetUrl,
        String storageProvider,
        String deliveryMode,
        int version,
        String contentType,
        long byteSize,
        String checksumSha256,
        int widthPx,
        int heightPx
) {
}
