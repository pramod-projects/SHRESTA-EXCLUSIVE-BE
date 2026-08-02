package com.shrestaexclusive.platform.asset;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AssetRepository {

    AssetSearchResponse search(String query, String categoryFamilyKey, String categoryProductTypeKey, String productSku, String status, int page, int size);

    Optional<AssetResponse> findByAssetKey(String assetKey);

    UUID insertUploadedAsset(StoredAsset storedAsset, AssetUploadRequest request);

    AssetReplacementTarget replacementTargetForUpdate(String assetKey);

    void replaceOriginal(UUID assetId, StoredAsset storedAsset);

    void replaceVariants(UUID assetId, List<GeneratedVariant> variants, String lqipDataUrl);

    void markReady(UUID assetId);

    void markFailed(UUID assetId, String errorMessage);

    void updateMetadata(String assetKey, AssetMetadataUpdateRequest request);

    void archive(String assetKey);

    void deletePermanently(String assetKey);

    List<String> findStorageKeysByAssetKey(String assetKey);

    void bulkAssignCategory(List<String> assetKeys, String categoryFamilyKey, String categoryProductTypeKey);
}
