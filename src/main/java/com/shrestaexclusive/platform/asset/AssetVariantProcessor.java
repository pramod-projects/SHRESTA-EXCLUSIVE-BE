package com.shrestaexclusive.platform.asset;

import java.io.IOException;
import java.util.List;

interface AssetVariantProcessor {

    ProcessedAsset process(StoredAsset original) throws IOException, InterruptedException;

    record ProcessedAsset(
            List<GeneratedVariant> variants,
            String lqipDataUrl
    ) {
        public ProcessedAsset {
            variants = List.copyOf(variants);
        }
    }
}
