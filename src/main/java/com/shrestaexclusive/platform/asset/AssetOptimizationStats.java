package com.shrestaexclusive.platform.asset;

public record AssetOptimizationStats(
        long originalBytes,
        long smallestVariantBytes,
        long bytesSavedAgainstSmallest,
        int percentSavedAgainstSmallest,
        int variantCount
) {
}
