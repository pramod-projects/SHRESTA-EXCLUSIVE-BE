package com.shrestaexclusive.platform.asset;

import java.util.List;

public record StorefrontUnreferencedAssetsResponse(
        List<AssetResponse> items,
        int page,
        int size,
        long total
) {

    public StorefrontUnreferencedAssetsResponse {
        items = List.copyOf(items);
    }
}
