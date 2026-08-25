package com.shrestaexclusive.platform.asset;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.shrestaexclusive.platform.kv.KvReadThroughCache;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeService;

@Service
public class AssetService {

    private static final java.time.Duration PRODUCT_MEDIA_RESERVATION_TTL = java.time.Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);
    public static final List<String> MEDIA_TABLES = List.of("media_assets");
    private static final List<String> STOREFRONT_UNREFERENCED_TABLES = List.of(
            "media_assets", "storefront_home_items", "storefront_home_item_gallery");
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");
    private static final TypeReference<AssetSearchResponse> ASSET_SEARCH_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<AssetResponse> ASSET_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<StorefrontUnreferencedAssetsResponse> STOREFRONT_UNREFERENCED_RESPONSE = new TypeReference<>() {
    };

    private final AssetRepository repository;
    private final R2ObjectStorageClient objectStorage;
    private final AssetStorageProperties properties;
    private final KvReadThroughCache kvCache;
    private final StorefrontHomeService storefrontHomeService;

    @Autowired
    AssetService(
            AssetRepository repository,
            R2ObjectStorageClient objectStorage,
            AssetStorageProperties properties,
            KvReadThroughCache kvCache,
            StorefrontHomeService storefrontHomeService
    ) {
        this.repository = repository;
        this.objectStorage = objectStorage;
        this.properties = properties;
        this.kvCache = kvCache;
        this.storefrontHomeService = storefrontHomeService;
    }

    @Transactional(readOnly = true)
    public AssetSearchResponse search(String query, String categoryFamilyKey, String categoryProductTypeKey, String productSku, String status, int page, int size) {
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.min(Math.max(1, size), 100);
        String cacheKey = String.join(":", normalized(query), normalized(categoryFamilyKey),
                normalized(categoryProductTypeKey), normalized(productSku), normalized(status),
                Integer.toString(normalizedPage), Integer.toString(normalizedSize));
        return kvCache.getOrLoad("asset-search", cacheKey, MEDIA_TABLES, ASSET_SEARCH_RESPONSE,
                () -> repository.search(query, categoryFamilyKey, categoryProductTypeKey, productSku, status,
                        normalizedPage, normalizedSize));
    }

    @Transactional(readOnly = true)
    public StorefrontUnreferencedAssetsResponse searchStorefrontUnreferenced(String query, String status, int page, int size) {
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.min(Math.max(1, size), 100);
        String cacheKey = String.join(":", normalized(query), normalized(status),
                Integer.toString(normalizedPage), Integer.toString(normalizedSize));
        return kvCache.getOrLoad("asset-storefront-unreferenced", cacheKey, STOREFRONT_UNREFERENCED_TABLES,
                STOREFRONT_UNREFERENCED_RESPONSE,
                () -> repository.searchStorefrontUnreferenced(query, status, normalizedPage, normalizedSize));
    }

    @Transactional(readOnly = true)
    public AssetResponse get(String assetKey) {
        return kvCache.getOrLoad("asset-detail", assetKey, MEDIA_TABLES, ASSET_RESPONSE,
                () -> repository.findByAssetKey(assetKey).orElseThrow(() -> new AssetNotFoundException(assetKey)));
    }

    @Transactional
    public ProductMediaReservationResponse reserveProductMedia(String actor) {
        if (!StringUtils.hasText(actor)) {
            throw new IllegalArgumentException("Admin actor is required");
        }
        UUID reservationId = UUID.randomUUID();
        String productId = "product-" + reservationId;
        Instant expiresAt = Instant.now().plus(PRODUCT_MEDIA_RESERVATION_TTL);
        repository.insertProductMediaReservation(reservationId, productId, actor.trim().toLowerCase(Locale.ROOT), expiresAt);
        return new ProductMediaReservationResponse(productId, expiresAt);
    }

    @Transactional
    public void consumeProductMediaReservation(String productId) {
        repository.consumeProductMediaReservation(productId);
    }

    @Transactional
        public void submitReservedProductMedia(
            String productId,
            String actor,
            String primaryMediaId,
            String primaryAssetKey,
            List<String> galleryMediaIds,
            List<String> galleryAssetKeys,
            String videoMediaId,
            String videoAssetKey
    ) {
        if (!StringUtils.hasText(actor)) {
            throw new IllegalArgumentException("Admin actor is required");
        }
        if (!repository.activeProductMediaReservationExists(productId)) {
            throw new IllegalArgumentException("An active product media reservation is required");
        }
        validateProductMediaSelection(productId, primaryMediaId, primaryAssetKey, galleryMediaIds,
                galleryAssetKeys, videoMediaId, videoAssetKey);
        if (!repository.submitProductMediaReservation(productId, actor.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("The product media reservation is expired or belongs to another admin");
        }
    }

    @Transactional
    public void validateSubmittedProductMedia(
            String productId,
            String primaryMediaId,
            String primaryAssetKey,
            List<String> galleryMediaIds,
            List<String> galleryAssetKeys,
            String videoMediaId,
            String videoAssetKey
    ) {
        if (!repository.submittedProductMediaReservationExists(productId)) {
            throw new IllegalArgumentException("A submitted product media reservation is required");
        }
        validateProductMediaSelection(productId, primaryMediaId, primaryAssetKey, galleryMediaIds,
                galleryAssetKeys, videoMediaId, videoAssetKey);
    }

    private void validateProductMediaSelection(
            String productId,
            String primaryMediaId,
            String primaryAssetKey,
            List<String> galleryMediaIds,
            List<String> galleryAssetKeys,
            String videoMediaId,
            String videoAssetKey
    ) {
        requireReadyProductMedia(productId, primaryMediaId, primaryAssetKey, "PRODUCT_IMAGE", "primary image");

        List<String> mediaIds = galleryMediaIds == null ? List.of() : galleryMediaIds;
        List<String> assetKeys = galleryAssetKeys == null ? List.of() : galleryAssetKeys;
        if (mediaIds.size() != assetKeys.size() || assetKeys.size() > 4) {
            throw new IllegalArgumentException("Gallery media IDs and asset keys must use the same four slots");
        }
        for (int slot = 0; slot < assetKeys.size(); slot++) {
            String mediaId = mediaIds.get(slot);
            String assetKey = assetKeys.get(slot);
            if (StringUtils.hasText(mediaId) != StringUtils.hasText(assetKey)) {
                throw new IllegalArgumentException("Each gallery image requires a matching media ID and asset key");
            }
            if (StringUtils.hasText(assetKey)) {
                requireReadyProductMedia(productId, mediaId, assetKey, "PRODUCT_IMAGE", "gallery image");
            }
        }

        if (StringUtils.hasText(videoMediaId) != StringUtils.hasText(videoAssetKey)) {
            throw new IllegalArgumentException("The product video requires a matching media ID and asset key");
        }
        if (StringUtils.hasText(videoAssetKey)) {
            requireReadyProductMedia(productId, videoMediaId, videoAssetKey, "PRODUCT_VIDEO", "product video");
        }
    }

    private void requireReadyProductMedia(
            String productId,
            String mediaId,
            String assetKey,
            String mediaType,
            String label
    ) {
        if (!StringUtils.hasText(mediaId) || !StringUtils.hasText(assetKey)) {
            throw new IllegalArgumentException("A READY " + label + " is required");
        }
        UUID parsedMediaId;
        try {
            parsedMediaId = UUID.fromString(mediaId.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("The " + label + " media ID is invalid");
        }
        if (!repository.readyProductMediaMatches(productId, parsedMediaId, assetKey.trim(), mediaType)) {
            throw new IllegalArgumentException("The " + label + " does not match the reserved product");
        }
    }

    @Transactional
    public void validateDisplayUpload(String mediaId, String assetKey, String mediaType, String actor) {
        UUID parsedMediaId = parseMediaId(mediaId, "display media");
        String normalizedActor = StringUtils.hasText(actor) ? actor.trim().toLowerCase(Locale.ROOT) : null;
        if (!repository.readyDisplayMediaMatches(parsedMediaId, assetKey, mediaType, normalizedActor)) {
            throw new IllegalArgumentException("The display upload does not match the submitting admin");
        }
    }

    @Transactional
    public void validateDisplayUpload(String mediaId, String assetKey, String mediaType) {
        if (!repository.readyDisplayMediaMatches(parseMediaId(mediaId, "display media"), assetKey, mediaType, null)) {
            throw new IllegalArgumentException("The display upload identity is invalid");
        }
    }

    @Transactional
    public void archiveDisplayUpload(String mediaId, String assetKey, String mediaType) {
        validateDisplayUpload(mediaId, assetKey, mediaType);
        archive(assetKey);
    }

    private UUID parseMediaId(String mediaId, String label) {
        if (!StringUtils.hasText(mediaId)) {
            throw new IllegalArgumentException("The " + label + " ID is required");
        }
        try {
            return UUID.fromString(mediaId.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("The " + label + " ID is invalid");
        }
    }

    @Transactional
    public void archiveReservedProductMedia(String productId) {
        for (String assetKey : repository.findProductMediaAssetKeys(productId)) {
            archive(assetKey);
        }
        repository.consumeProductMediaReservation(productId);
    }

    @Transactional
    public int expireAbandonedProductMediaReservations() {
        List<String> productIds = repository.findExpiredActiveProductReservations(100);
        for (String productId : productIds) {
            for (String assetKey : repository.findProductMediaAssetKeys(productId)) {
                archive(assetKey);
            }
            repository.expireProductMediaReservation(productId);
            log.info("event=PRODUCT_MEDIA_RESERVATION_EXPIRED productId={} status=EXPIRED", productId);
        }
        return productIds.size();
    }

    @Transactional
    public int expireAbandonedDisplayUploads() {
        List<String> assetKeys = repository.findAbandonedDisplayAssetKeys(Instant.now().minus(java.time.Duration.ofHours(24)), 100);
        for (String assetKey : assetKeys) {
            archive(assetKey);
            log.info("event=DISPLAY_MEDIA_UPLOAD_EXPIRED assetKey={} status=ARCHIVED", assetKey);
        }
        return assetKeys.size();
    }

    @Transactional
    public MediaUploadAuthorizationResponse authorizeUpload(MediaUploadAuthorizationRequest request, String actor) {
        validateUpload(request, actor);
        UUID mediaId = UUID.randomUUID();
        String objectKey = objectKey(request, mediaId, extensionFor(request.contentType()));
        String assetKey = "media-" + mediaId.toString().replace("-", "");
        R2ObjectStorageClient.PresignedUpload signed = objectStorage.presignPut(mediaId.toString(), objectKey,
                request.contentType());
        repository.insertPendingUpload(mediaId, assetKey, objectKey, request, actor, signed.expiresAt());
        log.info("event=MEDIA_UPLOAD_URL_CREATED mediaId={} productId={} mediaType={} status=PENDING_UPLOAD actor={}",
            mediaId, request.productId(), request.mediaType(), actor);
        publishMediaTablesAfterCommit();
        return new MediaUploadAuthorizationResponse(mediaId.toString(), assetKey, objectKey, signed.url(),
                signed.expiresAt(), request.contentType(), signed.requiredHeaders());
    }

    @Transactional(noRollbackFor = MediaUploadFailedException.class)
    public AssetResponse completeUpload(String mediaId, String actor) {
        if (!StringUtils.hasText(actor)) {
            throw new IllegalArgumentException("Admin actor is required");
        }
        MediaUploadRecord upload = repository.pendingUploadForUpdate(
                mediaId, actor.trim().toLowerCase(Locale.ROOT));
        if ("READY".equals(upload.status())) {
            return get(upload.assetKey());
        }
        if (!"PENDING_UPLOAD".equals(upload.status())) {
            throw new IllegalStateException("Media upload is not pending");
        }
        if (upload.uploadExpiresAt() != null && Instant.now().isAfter(upload.uploadExpiresAt())) {
            repository.markFailed(upload.id(), "Upload authorization expired before completion");
            log.warn("event=MEDIA_UPLOAD_FAILED mediaId={} productId={} mediaType={} status=FAILED reason=authorization_expired",
                    upload.id(), upload.productId(), upload.mediaType());
            deleteObjectsAfterCommit(List.of(upload.objectKey()));
            throw new MediaUploadFailedException("Media upload authorization expired");
        }
        R2ObjectStorageClient.HeadedObject object;
        try {
            object = objectStorage.head(upload.objectKey());
        } catch (MediaObjectNotFoundException exception) {
            repository.markFailed(upload.id(), "Uploaded object was not found during completion");
            log.warn("event=MEDIA_UPLOAD_FAILED mediaId={} productId={} mediaType={} status=FAILED reason=object_not_found",
                    upload.id(), upload.productId(), upload.mediaType());
            throw new MediaUploadFailedException("Uploaded media object was not found");
        }
        if (object.byteSize() != upload.byteSize()) {
            repository.markFailed(upload.id(), "Uploaded byte size does not match authorization");
            log.warn("event=MEDIA_UPLOAD_FAILED mediaId={} productId={} mediaType={} status=FAILED reason=byte_size_mismatch",
                    upload.id(), upload.productId(), upload.mediaType());
            deleteObjectsAfterCommit(List.of(upload.objectKey()));
            throw new MediaUploadFailedException("Uploaded object size does not match authorization");
        }
        if (!upload.contentType().equalsIgnoreCase(object.contentType())) {
            repository.markFailed(upload.id(), "Uploaded content type does not match authorization");
            log.warn("event=MEDIA_UPLOAD_FAILED mediaId={} productId={} mediaType={} status=FAILED reason=content_type_mismatch",
                    upload.id(), upload.productId(), upload.mediaType());
            deleteObjectsAfterCommit(List.of(upload.objectKey()));
            throw new MediaUploadFailedException("Uploaded object content type does not match authorization");
        }
        if (!mediaId.equals(object.metadata().get("media-id"))) {
            repository.markFailed(upload.id(), "Uploaded media identity metadata is missing or invalid");
            log.warn("event=MEDIA_UPLOAD_FAILED mediaId={} productId={} mediaType={} status=FAILED reason=media_identity_mismatch",
                    upload.id(), upload.productId(), upload.mediaType());
            deleteObjectsAfterCommit(List.of(upload.objectKey()));
            throw new MediaUploadFailedException("Uploaded object identity does not match authorization");
        }
        repository.completeUpload(upload.id(), object.etag());
        log.info("event=MEDIA_UPLOAD_COMPLETED mediaId={} productId={} mediaType={} status=READY",
            upload.id(), upload.productId(), upload.mediaType());
        publishMediaTablesAfterCommit();
        return repository.findByAssetKey(upload.assetKey())
                .orElseThrow(() -> new AssetNotFoundException(upload.assetKey()));
    }

    @Transactional
    public AssetResponse updateMetadata(String assetKey, AssetMetadataUpdateRequest request) {
        repository.updateMetadata(assetKey, request);
        AssetResponse response = repository.findByAssetKey(assetKey)
                .orElseThrow(() -> new AssetNotFoundException(assetKey));
        publishMediaTablesAfterCommit();
        return response;
    }

    @Transactional
    public void archive(String assetKey) {
        List<String> storageKeys = repository.findStorageKeysByAssetKey(assetKey);
        repository.archive(assetKey);
        publishMediaTablesAfterCommit();
        deleteObjectsAfterCommit(storageKeys);
        log.info("event=MEDIA_DELETED assetKey={} lifecycle=ARCHIVED", assetKey);
    }

    @Transactional
    public void archiveIfUnreferenced(String assetKey, String replacementAssetKey) {
        if (!StringUtils.hasText(assetKey) || assetKey.equals(replacementAssetKey)
                || repository.isReferencedByStorefront(assetKey)) {
            return;
        }
        archive(assetKey);
    }

    @Transactional(readOnly = true)
    public void validateStorefrontProductMediaLink(String itemKey, String assetKey, String mediaType) {
        if (!repository.storefrontProductMediaLinkEligible(itemKey, assetKey, mediaType)) {
            throw new InvalidAssetRequestException("The asset cannot be linked to this storefront product");
        }
        validateMediaContentKindMatchesSlot(assetKey, mediaType);
    }

    private void validateMediaContentKindMatchesSlot(String assetKey, String mediaType) {
        String contentType = repository.findContentTypeByAssetKey(assetKey)
                .orElseThrow(() -> new InvalidAssetRequestException("The asset cannot be linked to this storefront product"));
        boolean video = mediaType.endsWith("_VIDEO");
        String normalizedContentType = contentType.toLowerCase(Locale.ROOT);
        boolean matchesSlot = video
                ? normalizedContentType.startsWith("video/")
                : normalizedContentType.startsWith("image/");
        if (!matchesSlot) {
            throw new InvalidAssetRequestException(video
                    ? "The VIDEO slot requires a video asset"
                    : "This slot requires an image asset");
        }
    }

    @Transactional
    public void reassignStorefrontProductMedia(String itemKey, String assetKey, String mediaType) {
        if (!repository.reassignStorefrontProductMedia(itemKey, assetKey, mediaType)) {
            throw new InvalidAssetRequestException("The asset cannot be linked to this storefront product");
        }
        publishMediaTablesAfterCommit();
    }

    @Transactional
    public void deletePermanently(String assetKey) {
        if (repository.isReferencedByStorefront(assetKey)) {
            throw new InvalidAssetRequestException(
                    "Asset is referenced by storefront content and cannot be permanently deleted.");
        }
        List<String> storageKeys = repository.findStorageKeysByAssetKey(assetKey);
        repository.deletePermanently(assetKey);
        publishMediaTablesAfterCommit();
        deleteObjectsAfterCommit(storageKeys);
        log.info("event=MEDIA_DELETED assetKey={} lifecycle=DELETED", assetKey);
    }

    @Transactional
    public AssetSearchResponse bulkAssignCategory(BulkCategoryAssignmentRequest request) {
        repository.bulkAssignCategory(request.assetKeys(), request.categoryFamilyKey(), request.categoryProductTypeKey());
        AssetSearchResponse response = repository.search(null, request.categoryFamilyKey(),
                request.categoryProductTypeKey(), null, null, 0, 50);
        publishMediaTablesAfterCommit();
        return response;
    }

    private void validateUpload(MediaUploadAuthorizationRequest request, String actor) {
        boolean video = request.mediaType().endsWith("_VIDEO");
        Set<String> allowedTypes = video ? VIDEO_TYPES : IMAGE_TYPES;
        if (!allowedTypes.contains(request.contentType().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported media content type");
        }
        if (video) {
            if (request.byteSize() > properties.getVideoMaxFileSize()) {
                throw new IllegalArgumentException("Video exceeds configured file-size limit");
            }
            if (request.durationSeconds() == null || request.durationSeconds() > 30) {
                throw new IllegalArgumentException("Video duration must be 30 seconds or less");
            }
            if ("PRODUCT_VIDEO".equals(request.mediaType())) {
                requireProduct(request.productId(), actor);
                if (repository.countProductMedia(request.productId(), "PRODUCT_VIDEO") >= 1) {
                    throw new IllegalArgumentException("A product can have at most one video");
                }
            }
            return;
        }
        if (request.widthPx() == null || request.heightPx() == null) {
            throw new IllegalArgumentException("Image dimensions are required");
        }
        if (request.widthPx() > properties.getCanonicalMaxWidth()
                || request.heightPx() > properties.getCanonicalMaxHeight()) {
            throw new IllegalArgumentException("Image exceeds configured canonical dimensions");
        }
        if (request.byteSize() > properties.getCanonicalMaxFileSize()) {
            throw new IllegalArgumentException("Image exceeds configured file-size limit");
        }
        if ("PRODUCT_IMAGE".equals(request.mediaType())) {
            requireProduct(request.productId(), actor);
            if (repository.countProductMedia(request.productId(), "PRODUCT_IMAGE") >= 5) {
                throw new IllegalArgumentException("A product can have at most five images");
            }
        }
    }

    private void requireProduct(String productId, String actor) {
        String normalizedActor = StringUtils.hasText(actor) ? actor.trim().toLowerCase(Locale.ROOT) : "";
        if (!StringUtils.hasText(productId) || !repository.productMediaTargetExists(productId, normalizedActor)) {
            throw new IllegalArgumentException("A valid product is required for product media");
        }
    }

    private String objectKey(MediaUploadAuthorizationRequest request, UUID mediaId, String extension) {
        if (request.mediaType().startsWith("DISPLAY_")) {
            String kind = request.mediaType().endsWith("_VIDEO") ? "videos" : "images";
            return "display/" + kind + "/" + mediaId + "." + extension;
        }
        String product = request.productId().replaceAll("[^A-Za-z0-9_-]", "-");
        String kind = "PRODUCT_VIDEO".equals(request.mediaType()) ? "videos" : "images";
        return "products/" + product + "/" + kind + "/" + mediaId + "." + extension;
    }

    private String extensionFor(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            default -> throw new IllegalArgumentException("Unsupported media content type");
        };
    }

    private void publishMediaTablesAfterCommit() {
        afterCommit(() -> {
            kvCache.invalidateTables(MEDIA_TABLES);
            storefrontHomeService.refreshHomeKv();
        });
    }

    private void deleteObjectsAfterCommit(List<String> storageKeys) {
        if (!storageKeys.isEmpty()) {
            afterCommit(() -> storageKeys.forEach(objectStorage::delete));
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? "_" : value.trim();
    }
}
