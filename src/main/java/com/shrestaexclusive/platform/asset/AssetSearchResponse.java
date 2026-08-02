package com.shrestaexclusive.platform.asset;

import java.util.List;

public record AssetSearchResponse(
        List<AssetResponse> assets,
        int page,
        int size,
        long total
) {

    public AssetSearchResponse {
        assets = List.copyOf(assets);
    }
}
