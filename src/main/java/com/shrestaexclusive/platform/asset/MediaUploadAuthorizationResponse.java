package com.shrestaexclusive.platform.asset;

import java.time.Instant;
import java.util.Map;

public record MediaUploadAuthorizationResponse(
        String mediaId,
        String assetKey,
        String objectKey,
        String uploadUrl,
        Instant expiresAt,
        String contentType,
        Map<String, String> requiredHeaders
) {
    public MediaUploadAuthorizationResponse {
        requiredHeaders = Map.copyOf(requiredHeaders);
    }
}