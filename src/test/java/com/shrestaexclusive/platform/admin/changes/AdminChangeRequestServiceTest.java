package com.shrestaexclusive.platform.admin.changes;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shrestaexclusive.platform.admin.testusers.AdminTestUserService;
import com.shrestaexclusive.platform.asset.AssetService;
import com.shrestaexclusive.platform.asset.InvalidAssetRequestException;

class AdminChangeRequestServiceTest {

    private final AdminChangeRequestRepository repository = mock(AdminChangeRequestRepository.class);
    private final AdminChangeRequestApplier applier = mock(AdminChangeRequestApplier.class);
    private final AssetService assetService = mock(AssetService.class);
    private final AdminTestUserService testUserService = mock(AdminTestUserService.class);
    private final AdminChangeRequestService service = new AdminChangeRequestService(repository, applier, assetService, testUserService);

    @Test
    void appliesPendingRequestBeforeApproving() {
        AdminChangeRequestResponse pending = response("PENDING_REVIEW");
        AdminChangeRequestResponse approved = response("APPROVED");
        AdminChangeRequestDecisionRequest decision = new AdminChangeRequestDecisionRequest("reviewer@shresta.local", "Approved");
        when(repository.findByRequestKey("acr-1")).thenReturn(Optional.of(pending));
        when(repository.approve("acr-1", "CHANGE_REVIEWER", decision)).thenReturn(approved);

        AdminChangeRequestResponse actual = service.approve("acr-1", "CHANGE_REVIEWER", decision);

        assertThat(actual.status()).isEqualTo("APPROVED");
        verify(applier).apply(pending, "reviewer@shresta.local");
        verify(repository).approve("acr-1", "CHANGE_REVIEWER", decision);
    }

    @Test
    void doesNotReapplyAlreadyReviewedRequest() {
        AdminChangeRequestResponse approved = response("APPROVED");
        AdminChangeRequestDecisionRequest decision = new AdminChangeRequestDecisionRequest("reviewer@shresta.local", "Approved again");
        when(repository.findByRequestKey("acr-1")).thenReturn(Optional.of(approved));

        AdminChangeRequestResponse actual = service.approve("acr-1", "CHANGE_REVIEWER", decision);

        assertThat(actual.status()).isEqualTo("APPROVED");
        verify(applier, never()).apply(approved, "reviewer@shresta.local");
        verify(repository, never()).approve("acr-1", "CHANGE_REVIEWER", decision);
    }

    @Test
    void archivesNewProductVideoWhenChangeIsRejected() {
        AdminChangeRequestResponse pending = new AdminChangeRequestResponse(
                "acr-video", "storefront-product-video", "storefront_home_items", "product-one:video",
                "UPDATE", "PENDING_REVIEW", "CHANGE_SUBMITTER", "admin@example.com",
                null, null, null, Map.of("demoVideoAssetKey", "media-new-video"),
                Instant.parse("2026-07-05T00:00:00Z"), Instant.parse("2026-07-05T00:00:00Z"), null);
        AdminChangeRequestDecisionRequest decision = new AdminChangeRequestDecisionRequest("reviewer@example.com", "Rejected");
        when(repository.findByRequestKey("acr-video")).thenReturn(Optional.of(pending));

        service.reject("acr-video", "CHANGE_REVIEWER", decision);

        verify(assetService).archive("media-new-video");
    }

    @Test
    void doesNotArchiveExistingDisplayReferenceWhenRejected() {
        AdminChangeRequestResponse pending = new AdminChangeRequestResponse(
                "acr-display", "storefront-display-media", "storefront_home_item", "hero-kalankari",
                "UPDATE", "PENDING_REVIEW", "CHANGE_SUBMITTER", "admin@example.com",
                null, null, null, Map.of("imageAssetKey", "existing-product-image"),
                Instant.parse("2026-07-05T00:00:00Z"), Instant.parse("2026-07-05T00:00:00Z"), null);
        AdminChangeRequestDecisionRequest decision = new AdminChangeRequestDecisionRequest("reviewer@example.com", "Rejected");
        when(repository.findByRequestKey("acr-display")).thenReturn(Optional.of(pending));

        service.reject("acr-display", "CHANGE_REVIEWER", decision);

        verify(assetService, never()).archiveDisplayUpload(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void archivesOnlyServerVerifiedUploadedDisplayMediaWhenRejected() {
        String mediaId = "9de62ed4-b829-48f2-a140-f1f899322d6a";
        AdminChangeRequestResponse pending = new AdminChangeRequestResponse(
                "acr-display-upload", "storefront-display-media", "storefront_home_item", "brand-shresta-exclusive",
                "UPDATE", "PENDING_REVIEW", "CHANGE_SUBMITTER", "admin@example.com",
                null, null, null, Map.of("videoAssetKey", "media-display-video", "videoMediaId", mediaId),
                Instant.parse("2026-07-05T00:00:00Z"), Instant.parse("2026-07-05T00:00:00Z"), null);
        AdminChangeRequestDecisionRequest decision = new AdminChangeRequestDecisionRequest("reviewer@example.com", "Rejected");
        when(repository.findByRequestKey("acr-display-upload")).thenReturn(Optional.of(pending));

        service.reject("acr-display-upload", "CHANGE_REVIEWER", decision);

        verify(assetService).archiveDisplayUpload(mediaId, "media-display-video", "DISPLAY_VIDEO");
    }

        @Test
        void upsertCannotBypassDisplayVideoUploadIdentityValidation() {
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
            "storefront-display-media", "storefront_home_item", "brand-shresta-exclusive", "UPDATE",
            "admin@example.com", Map.of("videoAssetKey", "media-other-admin-video"));

        assertThatThrownBy(() -> service.createOrUpdatePending("CHANGE_SUBMITTER", "admin@example.com", request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A verified display video upload is required");

        verify(repository, never()).upsertPending(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
        }

        @Test
        void upsertValidatesDisplayUploadAgainstSubmittingActor() {
        String mediaId = "9de62ed4-b829-48f2-a140-f1f899322d6a";
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
            "storefront-display-media", "storefront_home_item", "brand-shresta-exclusive", "UPDATE",
            "admin@example.com", Map.of("videoAssetKey", "media-display-video", "videoMediaId", mediaId));

        service.createOrUpdatePending("CHANGE_SUBMITTER", "Admin@Example.com ", request);

        verify(assetService).validateDisplayUpload(
            mediaId, "media-display-video", "DISPLAY_VIDEO", "Admin@Example.com ");
        verify(repository).upsertPending(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("CHANGE_SUBMITTER"),
            org.mockito.ArgumentMatchers.eq(request));
        }

        @Test
        void upsertArchivesSupersededStagedMedia() {
        AdminChangeRequestResponse existing = new AdminChangeRequestResponse(
            "acr-existing", "storefront-product-image", "storefront_home_item", "product-one", "UPDATE",
            "PENDING_REVIEW", "CHANGE_SUBMITTER", "admin@example.com", null, null, null,
            Map.of("newAssetKey", "media-old-staged", "mediaId", "old-media-id"),
            Instant.parse("2026-07-05T00:00:00Z"), Instant.parse("2026-07-05T00:00:00Z"), null);
        AdminChangeRequestCreateRequest replacement = new AdminChangeRequestCreateRequest(
            "storefront-product-image", "storefront_home_item", "product-one", "UPDATE",
            "admin@example.com", Map.of("newAssetKey", "media-new-staged", "mediaId", "new-media-id"));
        when(repository.findFirstPending("product-one", "storefront-product-image"))
            .thenReturn(Optional.of(existing));

        service.createOrUpdatePending("CHANGE_SUBMITTER", "admin@example.com", replacement);

        verify(assetService).archiveIfUnreferenced("media-old-staged", "media-new-staged");
        }

    @Test
    void validatesProductMediaLinkAgainstAssetEligibility() {
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
                "storefront-product-media-link", "storefront_home_items", "product-one", "UPDATE",
                "admin@example.com",
                Map.of("assetKey", "media-free", "mediaId", "550e8400-e29b-41d4-a716-446655440000", "slot", "PRIMARY"));

        service.create("CHANGE_SUBMITTER", "admin@example.com", request);

        verify(assetService).validateStorefrontProductMediaLink("product-one", "media-free", "PRODUCT_IMAGE");
        verify(repository).create(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("CHANGE_SUBMITTER"),
                org.mockito.ArgumentMatchers.eq(request));
    }

    @Test
    void rejectsProductMediaLinkForIneligibleAsset() {
        org.mockito.Mockito.doThrow(new com.shrestaexclusive.platform.asset.InvalidAssetRequestException(
                        "The asset cannot be linked to this storefront product"))
                .when(assetService).validateStorefrontProductMediaLink("product-one", "media-referenced", "PRODUCT_IMAGE");
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
                "storefront-product-media-link", "storefront_home_items", "product-one", "UPDATE",
                "admin@example.com",
                Map.of("assetKey", "media-referenced", "mediaId", "550e8400-e29b-41d4-a716-446655440000", "slot", "GALLERY_2"));

        assertThatThrownBy(() -> service.createOrUpdatePending("CHANGE_SUBMITTER", "admin@example.com", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The asset cannot be linked to this storefront product");

        verify(repository, never()).upsertPending(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsProductMediaLinkWithUnknownSlot() {
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
                "storefront-product-media-link", "storefront_home_items", "product-one", "UPDATE",
                "admin@example.com",
                Map.of("assetKey", "media-free", "mediaId", "550e8400-e29b-41d4-a716-446655440000", "slot", "THUMBNAIL"));

        assertThatThrownBy(() -> service.create("CHANGE_SUBMITTER", "admin@example.com", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("slot must be one of PRIMARY, VIDEO, GALLERY_1, GALLERY_2, GALLERY_3, GALLERY_4");

        verify(repository, never()).create(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsProductMediaLinkWithWrongEntityShape() {
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
                "storefront-product-media-link", "storefront_home_item", "product-one", "CREATE",
                "admin@example.com",
                Map.of("assetKey", "media-free", "mediaId", "550e8400-e29b-41d4-a716-446655440000", "slot", "PRIMARY"));

        assertThatThrownBy(() -> service.create("CHANGE_SUBMITTER", "admin@example.com", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product media links must update a storefront home item");

        verify(repository, never()).create(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMediaLinkSubmissionWhenADifferentSlotIsPending() {
        AdminChangeRequestResponse existing = new AdminChangeRequestResponse(
                "acr-existing", "storefront-product-media-link", "storefront_home_items", "product-one", "UPDATE",
                "PENDING_REVIEW", "CHANGE_SUBMITTER", "admin@example.com", null, null, null,
                Map.of("assetKey", "media-old", "slot", "PRIMARY"),
                Instant.parse("2026-07-05T00:00:00Z"), Instant.parse("2026-07-05T00:00:00Z"), null);
        when(repository.findFirstPending("product-one", "storefront-product-media-link"))
                .thenReturn(Optional.of(existing));
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
                "storefront-product-media-link", "storefront_home_items", "product-one", "UPDATE",
                "admin@example.com",
                Map.of("assetKey", "media-new", "mediaId", "550e8400-e29b-41d4-a716-446655440000", "slot", "GALLERY_2"));

        assertThatThrownBy(() -> service.createOrUpdatePending("CHANGE_SUBMITTER", "admin@example.com", request))
                .isInstanceOf(InvalidAssetRequestException.class)
                .hasMessage("A pending media link request already exists for this product (slot PRIMARY)."
                        + " Approve or reject it before submitting another.");

        verify(repository, never()).upsertPending(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void upsertsSameSlotMediaLinkResubmission() {
        AdminChangeRequestResponse existing = new AdminChangeRequestResponse(
                "acr-existing", "storefront-product-media-link", "storefront_home_items", "product-one", "UPDATE",
                "PENDING_REVIEW", "CHANGE_SUBMITTER", "admin@example.com", null, null, null,
                Map.of("assetKey", "media-old", "slot", "PRIMARY"),
                Instant.parse("2026-07-05T00:00:00Z"), Instant.parse("2026-07-05T00:00:00Z"), null);
        when(repository.findFirstPending("product-one", "storefront-product-media-link"))
                .thenReturn(Optional.of(existing));
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
                "storefront-product-media-link", "storefront_home_items", "product-one", "UPDATE",
                "admin@example.com",
                Map.of("assetKey", "media-new", "mediaId", "550e8400-e29b-41d4-a716-446655440000", "slot", "PRIMARY"));
        AdminChangeRequestResponse replaced = new AdminChangeRequestResponse(
                "acr-existing", "storefront-product-media-link", "storefront_home_items", "product-one", "UPDATE",
                "PENDING_REVIEW", "CHANGE_SUBMITTER", "admin@example.com", null, null, null,
                Map.of("assetKey", "media-new", "mediaId", "550e8400-e29b-41d4-a716-446655440000", "slot", "PRIMARY"),
                Instant.parse("2026-07-05T00:00:00Z"), Instant.parse("2026-07-05T00:00:00Z"), null);
        when(repository.upsertPending(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("CHANGE_SUBMITTER"),
                org.mockito.ArgumentMatchers.eq(request))).thenReturn(replaced);

        AdminChangeRequestResponse result = service.createOrUpdatePending("CHANGE_SUBMITTER", "admin@example.com", request);

        assertThat(result.requestKey()).isEqualTo("acr-existing");
        assertThat(result.payload()).containsEntry("assetKey", "media-new");
        verify(repository).upsertPending(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("CHANGE_SUBMITTER"),
                org.mockito.ArgumentMatchers.eq(request));
    }

    @Test
    void testUserCreateRequestDelegatesToTestUserValidation() {
        Map<String, Object> payload = Map.of("displayName", "QA User", "email", "qa.user@example.com");
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
                "test-user-management", "customer_accounts", "qa.user@example.com", "CREATE",
                "admin@example.com", payload);

        service.create("CHANGE_MANAGER", "admin@example.com", request);

        verify(testUserService).validateCreateSubmit("qa.user@example.com", payload);
        verify(repository).create(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("CHANGE_MANAGER"),
                org.mockito.ArgumentMatchers.eq(request));
    }

    @Test
    void testUserDeleteRequestDelegatesToTestUserValidation() {
        String customerId = "550e8400-e29b-41d4-a716-446655440000";
        Map<String, Object> payload = Map.of("customerId", customerId, "reason", "lane cleanup");
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
                "test-user-management", "customer_accounts", customerId, "DELETE",
                "admin@example.com", payload);

        service.createOrUpdatePending("CHANGE_MANAGER", "admin@example.com", request);

        verify(testUserService).validateDeleteSubmit(customerId, payload);
        verify(repository).upsertPending(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("CHANGE_MANAGER"),
                org.mockito.ArgumentMatchers.eq(request));
    }

    @Test
    void testUserRequestRejectsWrongEntityType() {
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
                "test-user-management", "customer_account", "qa.user@example.com", "CREATE",
                "admin@example.com", Map.of("displayName", "QA User", "email", "qa.user@example.com"));

        assertThatThrownBy(() -> service.create("CHANGE_MANAGER", "admin@example.com", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Test user management must target customer_accounts");

        verify(repository, never()).create(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void testUserRequestRejectsSubmittedOtpSecret() {
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
                "test-user-management", "customer_accounts", "qa.user@example.com", "CREATE",
                "admin@example.com",
                Map.of("displayName", "QA User", "email", "qa.user@example.com", "otp", "12345678"));

        assertThatThrownBy(() -> service.create("CHANGE_MANAGER", "admin@example.com", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OTP is minted by the platform at approval and must not be submitted");

        verify(repository, never()).create(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void testUserRequestRejectsUnsupportedAction() {
        AdminChangeRequestCreateRequest request = new AdminChangeRequestCreateRequest(
                "test-user-management", "customer_accounts", "qa.user@example.com", "UPDATE",
                "admin@example.com", Map.of("displayName", "QA User", "email", "qa.user@example.com"));

        assertThatThrownBy(() -> service.create("CHANGE_MANAGER", "admin@example.com", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("test-user-management supports CREATE and DELETE only");
    }

    private AdminChangeRequestResponse response(String status) {
        return new AdminChangeRequestResponse(
                "acr-1",
                "asset-removal",
                "media_asset",
                "hero-silk-saree-maroon-gold",
                "ARCHIVE",
                status,
                "CHANGE_SUBMITTER",
                "SHRESTA asset admin",
                null,
                null,
                null,
                Map.of("assetKey", "hero-silk-saree-maroon-gold"),
                Instant.parse("2026-07-05T00:00:00Z"),
                Instant.parse("2026-07-05T00:00:00Z"),
                null
        );
    }
}
