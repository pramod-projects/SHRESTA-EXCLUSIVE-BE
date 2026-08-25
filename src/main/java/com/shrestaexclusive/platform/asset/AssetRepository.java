package com.shrestaexclusive.platform.asset;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AssetRepository {

    boolean productMediaTargetExists(String productId, String actor);

    void insertProductMediaReservation(UUID reservationId, String productId, String actor, java.time.Instant expiresAt);

    void consumeProductMediaReservation(String productId);

    boolean activeProductMediaReservationExists(String productId);

    boolean submittedProductMediaReservationExists(String productId);

    boolean submitProductMediaReservation(String productId, String actor);

    List<String> findExpiredActiveProductReservations(int limit);

    List<String> findAbandonedDisplayAssetKeys(java.time.Instant createdBefore, int limit);

    void expireProductMediaReservation(String productId);

    boolean readyProductMediaMatches(String productId, UUID mediaId, String assetKey, String mediaType);

    boolean readyDisplayMediaMatches(UUID mediaId, String assetKey, String mediaType, String actor);

    List<String> findProductMediaAssetKeys(String productId);

    long countProductMedia(String productId, String mediaType);

    MediaUploadRecord insertPendingUpload(
            UUID assetId,
            String assetKey,
            String objectKey,
            MediaUploadAuthorizationRequest request,
            String actor,
            java.time.Instant expiresAt
    );

    MediaUploadRecord pendingUploadForUpdate(String mediaId, String actor);

    void completeUpload(UUID assetId, String etag);

    AssetSearchResponse search(String query, String categoryFamilyKey, String categoryProductTypeKey, String productSku, String status, int page, int size);

    Optional<AssetResponse> findByAssetKey(String assetKey);

    Optional<String> findContentTypeByAssetKey(String assetKey);

    void markFailed(UUID assetId, String errorMessage);

    void updateMetadata(String assetKey, AssetMetadataUpdateRequest request);

    void archive(String assetKey);

    void deletePermanently(String assetKey);

    List<String> findStorageKeysByAssetKey(String assetKey);

    boolean isReferencedByStorefront(String assetKey);

    StorefrontUnreferencedAssetsResponse searchStorefrontUnreferenced(String query, String status, int page, int size);

    boolean storefrontProductMediaLinkEligible(String itemKey, String assetKey, String mediaType);

    boolean reassignStorefrontProductMedia(String itemKey, String assetKey, String mediaType);

    void bulkAssignCategory(List<String> assetKeys, String categoryFamilyKey, String categoryProductTypeKey);
}
