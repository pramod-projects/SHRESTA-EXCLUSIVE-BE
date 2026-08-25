package com.shrestaexclusive.platform.asset;

import java.util.List;

public record AssetSearchResponse(
        List<AssetResponse> assets,
        int page,
        int size,
    long total,
    long systemTotal,
    long systemImageTotal,
    long systemVideoTotal,
    long systemOtherTotal,
    long systemReferencedTotal,
    long systemUnreferencedTotal
) {

    public AssetSearchResponse {
        assets = List.copyOf(assets);
    }
}
