package com.shrestaexclusive.platform.asset;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shrestaexclusive.platform.kv.KvReadThroughCache;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeService;

class AssetServiceTest {

    private AssetRepository repository;
    private R2ObjectStorageClient objectStorage;
    private AssetService service;

        @Test
        void derivesObjectCacheControlFromConfiguredBrowserMaxAge() {
                AssetStorageProperties properties = new AssetStorageProperties();
                properties.setBrowserCacheMaxAgeSeconds(2_592_000);

                assertThat(properties.getObjectCacheControl()).isEqualTo("public,max-age=2592000");

                properties.setBrowserCacheMaxAgeSeconds(604_800);
                assertThat(properties.getObjectCacheControl()).isEqualTo("public,max-age=604800");
        }

    @BeforeEach
        public void setUp() {
        repository = mock(AssetRepository.class);
        objectStorage = mock(R2ObjectStorageClient.class);
        AssetStorageProperties properties = new AssetStorageProperties();
        properties.setCanonicalMaxWidth(4000);
        properties.setCanonicalMaxHeight(5333);
        properties.setCanonicalMaxFileSize(15_000_000);
        properties.setVideoMaxFileSize(100_000_000);
        service = new AssetService(
                repository,
                objectStorage,
                properties,
                mock(KvReadThroughCache.class),
                mock(StorefrontHomeService.class)
        );
    }

    @Test
    void generatesANewImmutableObjectKeyForEveryAuthorization() {
        when(repository.productMediaTargetExists("product-one", "admin@example.com")).thenReturn(true);
        when(objectStorage.presignPut(any(), any(), eq("image/webp")))
                .thenReturn(new R2ObjectStorageClient.PresignedUpload(
                        "https://r2.example/signed",
                        Instant.parse("2026-08-13T12:10:00Z"),
                        Map.of("content-type", "image/webp")
                ));
        MediaUploadAuthorizationRequest request = imageRequest();

        MediaUploadAuthorizationResponse first = service.authorizeUpload(request, "admin@example.com");
        MediaUploadAuthorizationResponse second = service.authorizeUpload(request, "admin@example.com");

        assertThat(first.mediaId()).isNotEqualTo(second.mediaId());
        assertThat(first.objectKey()).isNotEqualTo(second.objectKey());
        assertThat(first.objectKey()).matches("products/product-one/images/[0-9a-f-]{36}\\.webp");
        assertThat(second.objectKey()).matches("products/product-one/images/[0-9a-f-]{36}\\.webp");
    }

        @Test
        void issuesBackendOwnedProductMediaReservationForAdminActor() {
                ProductMediaReservationResponse reservation = service.reserveProductMedia("Admin@Example.com ");

                assertThat(reservation.productId()).matches("product-[0-9a-f-]{36}");
                assertThat(reservation.expiresAt()).isAfter(Instant.now().plusSeconds(23 * 60 * 60));
                ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
                verify(repository).insertProductMediaReservation(
                                id.capture(), eq(reservation.productId()), eq("admin@example.com"), eq(reservation.expiresAt()));
                assertThat(reservation.productId()).isEqualTo("product-" + id.getValue());
        }

        @Test
        void rejectsProductReservationWithoutAdminActor() {
                assertThatThrownBy(() -> service.reserveProductMedia(" "))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("Admin actor is required");
        }

        @Test
        void validatesEveryReservedProductMediaIdAgainstItsAssetKey() {
                UUID primaryId = UUID.randomUUID();
                UUID galleryId = UUID.randomUUID();
                when(repository.activeProductMediaReservationExists("product-reserved")).thenReturn(true);
                when(repository.readyProductMediaMatches("product-reserved", primaryId, "media-primary", "PRODUCT_IMAGE"))
                                .thenReturn(true);
                when(repository.readyProductMediaMatches("product-reserved", galleryId, "media-gallery", "PRODUCT_IMAGE"))
                                .thenReturn(true);
                when(repository.submitProductMediaReservation("product-reserved", "admin@example.com")).thenReturn(true);

                service.submitReservedProductMedia(
                                "product-reserved", "Admin@Example.com ", primaryId.toString(), "media-primary",
                                List.of(galleryId.toString()), List.of("media-gallery"), null, null);

                verify(repository).readyProductMediaMatches(
                                "product-reserved", primaryId, "media-primary", "PRODUCT_IMAGE");
                verify(repository).readyProductMediaMatches(
                                "product-reserved", galleryId, "media-gallery", "PRODUCT_IMAGE");
                verify(repository).submitProductMediaReservation("product-reserved", "admin@example.com");
        }

        @Test
        void rejectsExpiredReservationAndMismatchedGallerySlots() {
                assertThatThrownBy(() -> service.submitReservedProductMedia(
                                "product-expired", "admin@example.com", UUID.randomUUID().toString(), "media-primary",
                                List.of(), List.of(), null, null))
                                .hasMessage("An active product media reservation is required");

                UUID primaryId = UUID.randomUUID();
                when(repository.activeProductMediaReservationExists("product-reserved")).thenReturn(true);
                when(repository.readyProductMediaMatches("product-reserved", primaryId, "media-primary", "PRODUCT_IMAGE"))
                                .thenReturn(true);
                assertThatThrownBy(() -> service.submitReservedProductMedia(
                                "product-reserved", "admin@example.com", primaryId.toString(), "media-primary",
                                List.of(), List.of("media-gallery"), null, null))
                                .hasMessage("Gallery media IDs and asset keys must use the same four slots");
        }

    @Test
    void rejectsAProductImageWhenFiveActiveUploadsAlreadyExist() {
        when(repository.productMediaTargetExists("product-one", "admin@example.com")).thenReturn(true);
        when(repository.countProductMedia("product-one", "PRODUCT_IMAGE")).thenReturn(5L);

        assertThatThrownBy(() -> service.authorizeUpload(imageRequest(), "admin@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A product can have at most five images");

        verify(objectStorage, never()).presignPut(any(), any(), any());
    }

        @Test
        void archivesOnlyProductOwnedMediaWhenReservationIsRejected() {
                when(repository.findProductMediaAssetKeys("product-reserved"))
                                .thenReturn(List.of("media-primary", "media-gallery"));

                service.archiveReservedProductMedia("product-reserved");

                verify(repository).archive("media-primary");
                verify(repository).archive("media-gallery");
                verify(repository).consumeProductMediaReservation("product-reserved");
        }

        @Test
        void expiresOnlyAbandonedActiveReservationsAndArchivesTheirMedia() {
                when(repository.findExpiredActiveProductReservations(100))
                                .thenReturn(List.of("product-abandoned"));
                when(repository.findProductMediaAssetKeys("product-abandoned"))
                                .thenReturn(List.of("media-abandoned"));

                assertThat(service.expireAbandonedProductMediaReservations()).isEqualTo(1);

                verify(repository).archive("media-abandoned");
                verify(repository).expireProductMediaReservation("product-abandoned");
        }

    @Test
    void rejectsUnsupportedContentType() {
        MediaUploadAuthorizationRequest request = new MediaUploadAuthorizationRequest(
                "product-one", "PRODUCT_IMAGE", "image/gif", "saree.gif",
                120_000, 1800, 2400, null, "Silk saree");

        assertThatThrownBy(() -> service.authorizeUpload(request, "admin@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported media content type");
    }

    @Test
    void rejectsImageBeyondConfiguredDimensionsOrFileSize() {
        MediaUploadAuthorizationRequest tooWide = new MediaUploadAuthorizationRequest(
                "product-one", "PRODUCT_IMAGE", "image/webp", "saree.webp",
                120_000, 4001, 2400, null, "Silk saree");
        MediaUploadAuthorizationRequest tooLarge = new MediaUploadAuthorizationRequest(
                "product-one", "PRODUCT_IMAGE", "image/webp", "saree.webp",
                15_000_001, 1800, 2400, null, "Silk saree");

        assertThatThrownBy(() -> service.authorizeUpload(tooWide, "admin@example.com"))
                .hasMessage("Image exceeds configured canonical dimensions");
        assertThatThrownBy(() -> service.authorizeUpload(tooLarge, "admin@example.com"))
                .hasMessage("Image exceeds configured file-size limit");
    }

    @Test
    void rejectsMediaForUnknownProduct() {
        when(repository.productMediaTargetExists("product-one", "admin@example.com")).thenReturn(false);

        assertThatThrownBy(() -> service.authorizeUpload(imageRequest(), "admin@example.com"))
                .hasMessage("A valid product is required for product media");
    }

    @Test
    void rejectsSecondProductVideoAndVideoOverThirtySeconds() {
        when(repository.productMediaTargetExists("product-one", "admin@example.com")).thenReturn(true);
        when(repository.countProductMedia("product-one", "PRODUCT_VIDEO")).thenReturn(1L);
        MediaUploadAuthorizationRequest video = new MediaUploadAuthorizationRequest(
                "product-one", "PRODUCT_VIDEO", "video/mp4", "drape.mp4",
                2_000_000, null, null, 30, "Product video");

        assertThatThrownBy(() -> service.authorizeUpload(video, "admin@example.com"))
                .hasMessage("A product can have at most one video");

        MediaUploadAuthorizationRequest tooLong = new MediaUploadAuthorizationRequest(
                "product-one", "PRODUCT_VIDEO", "video/mp4", "drape.mp4",
                2_000_000, null, null, 31, "Product video");
        assertThatThrownBy(() -> service.authorizeUpload(tooLong, "admin@example.com"))
                .hasMessage("Video duration must be 30 seconds or less");
    }

    @Test
    void authorizesDisplayImagesWithoutProductReservation() {
        when(objectStorage.presignPut(any(), any(), eq("image/webp")))
                .thenReturn(new R2ObjectStorageClient.PresignedUpload(
                        "https://r2.example/signed",
                        Instant.parse("2026-08-13T12:10:00Z"),
                        Map.of("content-type", "image/webp")
                ));
        MediaUploadAuthorizationRequest displayImage = new MediaUploadAuthorizationRequest(
                null, "DISPLAY_IMAGE", "image/webp", "logo.webp",
                20_000, 600, 200, null, "Shresta logo");

        MediaUploadAuthorizationResponse response = service.authorizeUpload(displayImage, "admin@example.com");

        assertThat(response.objectKey()).matches("display/images/[0-9a-f-]{36}\\.webp");
        verify(repository, never()).productMediaTargetExists(any(), any());
    }

    @Test
    void refusesPermanentDeleteWhenStorefrontStillReferencesAsset() {
        when(repository.isReferencedByStorefront("media-referenced")).thenReturn(true);

        assertThatThrownBy(() -> service.deletePermanently("media-referenced"))
                .isInstanceOf(InvalidAssetRequestException.class)
                .hasMessage("Asset is referenced by storefront content and cannot be permanently deleted.");

        verify(repository, never()).deletePermanently(any());
    }

    @Test
    void permanentlyDeletesUnreferencedAsset() {
        when(repository.isReferencedByStorefront("media-unreferenced")).thenReturn(false);
        when(repository.findStorageKeysByAssetKey("media-unreferenced")).thenReturn(List.of());

        service.deletePermanently("media-unreferenced");

        verify(repository).deletePermanently("media-unreferenced");
    }

    @Test
    void rejectsMediaLinkForIneligibleAsset() {
        when(repository.storefrontProductMediaLinkEligible("product-one", "media-referenced", "PRODUCT_IMAGE"))
                .thenReturn(false);

        assertThatThrownBy(() -> service.validateStorefrontProductMediaLink("product-one", "media-referenced", "PRODUCT_IMAGE"))
                .isInstanceOf(InvalidAssetRequestException.class)
                .hasMessage("The asset cannot be linked to this storefront product");
    }

    @Test
    void rejectsImageAssetForVideoMediaLinkSlot() {
        when(repository.storefrontProductMediaLinkEligible("product-one", "media-image", "PRODUCT_VIDEO"))
                .thenReturn(true);
        when(repository.findContentTypeByAssetKey("media-image")).thenReturn(Optional.of("image/webp"));

        assertThatThrownBy(() -> service.validateStorefrontProductMediaLink("product-one", "media-image", "PRODUCT_VIDEO"))
                .isInstanceOf(InvalidAssetRequestException.class)
                .hasMessage("The VIDEO slot requires a video asset");
    }

    @Test
    void rejectsVideoAssetForImageMediaLinkSlot() {
        when(repository.storefrontProductMediaLinkEligible("product-one", "media-video", "PRODUCT_IMAGE"))
                .thenReturn(true);
        when(repository.findContentTypeByAssetKey("media-video")).thenReturn(Optional.of("video/mp4"));

        assertThatThrownBy(() -> service.validateStorefrontProductMediaLink("product-one", "media-video", "PRODUCT_IMAGE"))
                .isInstanceOf(InvalidAssetRequestException.class)
                .hasMessage("This slot requires an image asset");
    }

    @Test
    void acceptsMatchingContentKindForMediaLinkSlots() {
        when(repository.storefrontProductMediaLinkEligible("product-one", "media-image", "PRODUCT_IMAGE"))
                .thenReturn(true);
        when(repository.findContentTypeByAssetKey("media-image")).thenReturn(Optional.of("image/webp"));
        when(repository.storefrontProductMediaLinkEligible("product-one", "media-video", "PRODUCT_VIDEO"))
                .thenReturn(true);
        when(repository.findContentTypeByAssetKey("media-video")).thenReturn(Optional.of("video/quicktime"));

        service.validateStorefrontProductMediaLink("product-one", "media-image", "PRODUCT_IMAGE");
        service.validateStorefrontProductMediaLink("product-one", "media-video", "PRODUCT_VIDEO");

        verify(repository).findContentTypeByAssetKey("media-image");
        verify(repository).findContentTypeByAssetKey("media-video");
    }

    @Test
    void completesOnlyAfterHeadMetadataMatchesAuthorization() {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        MediaUploadRecord upload = pendingUpload(id, 120_000);
        AssetResponse ready = readyAsset();
        when(repository.pendingUploadForUpdate(id.toString(), "admin@example.com")).thenReturn(upload);
        when(objectStorage.head(upload.objectKey())).thenReturn(new R2ObjectStorageClient.HeadedObject(
                120_000,
                "image/webp",
                "etag-1",
                Map.of("media-id", id.toString())
        ));
        when(repository.findByAssetKey(upload.assetKey())).thenReturn(Optional.of(ready));

        assertThat(service.completeUpload(id.toString(), "Admin@Example.com ")).isEqualTo(ready);

        verify(repository).completeUpload(id, "etag-1");
        verify(repository, never()).markFailed(any(), any());
    }

    @Test
    void marksUploadFailedWhenHeadSizeDoesNotMatch() {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        MediaUploadRecord upload = pendingUpload(id, 120_000);
        when(repository.pendingUploadForUpdate(id.toString(), "admin@example.com")).thenReturn(upload);
        when(objectStorage.head(upload.objectKey())).thenReturn(new R2ObjectStorageClient.HeadedObject(
                119_999,
                "image/webp",
                "etag-1",
                Map.of("media-id", id.toString())
        ));

        assertThatThrownBy(() -> service.completeUpload(id.toString(), "admin@example.com"))
                .isInstanceOf(MediaUploadFailedException.class)
                .hasMessage("Uploaded object size does not match authorization");

        verify(repository).markFailed(id, "Uploaded byte size does not match authorization");
        verify(repository, never()).completeUpload(any(), any());
    }

        @Test
        void marksUploadFailedWhenR2ObjectDoesNotExist() {
                UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
                MediaUploadRecord upload = pendingUpload(id, 120_000);
                when(repository.pendingUploadForUpdate(id.toString(), "admin@example.com")).thenReturn(upload);
                when(objectStorage.head(upload.objectKey())).thenThrow(new MediaObjectNotFoundException(new RuntimeException()));

                assertThatThrownBy(() -> service.completeUpload(id.toString(), "admin@example.com"))
                                .isInstanceOf(MediaUploadFailedException.class)
                                .hasMessage("Uploaded media object was not found");

                verify(repository).markFailed(id, "Uploaded object was not found during completion");
                verify(repository, never()).completeUpload(any(), any());
        }

    private MediaUploadAuthorizationRequest imageRequest() {
        return new MediaUploadAuthorizationRequest(
                "product-one",
                "PRODUCT_IMAGE",
                "image/webp",
                "saree.webp",
                120_000,
                1800,
                2400,
                null,
                "Silk saree"
        );
    }

    private MediaUploadRecord pendingUpload(UUID id, long byteSize) {
        return new MediaUploadRecord(
                id,
                id.toString(),
                "media-550e8400e29b41d4a716446655440000",
                "products/product-one/images/550e8400-e29b-41d4-a716-446655440000.webp",
                "product-one",
                "PRODUCT_IMAGE",
                "image/webp",
                byteSize,
                Instant.now().plusSeconds(600),
                "PENDING_UPLOAD"
        );
    }

    private AssetResponse readyAsset() {
        return new AssetResponse(
                "media-550e8400e29b41d4a716446655440000",
                "saree.webp",
                "https://media.example/products/product-one/images/550e8400-e29b-41d4-a716-446655440000.webp",
                "Silk saree",
                null,
                null,
                "product-one",
                "READY",
                1,
                1800,
                2400,
                120_000,
                "image/webp",
                "cloudflare-r2",
                List.of(),
                null,
                null
        );
    }
}
