package com.shrestaexclusive.platform.asset;

import java.time.Instant;
import java.util.UUID;

record MediaUploadRecord(
        UUID id,
        String mediaId,
        String assetKey,
        String objectKey,
        String productId,
        String mediaType,
        String contentType,
        long byteSize,
        Instant uploadExpiresAt,
        String status
) {
}