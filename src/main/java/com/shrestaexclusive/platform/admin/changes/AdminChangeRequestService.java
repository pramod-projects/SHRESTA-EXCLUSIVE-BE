package com.shrestaexclusive.platform.admin.changes;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shrestaexclusive.platform.admin.testusers.AdminTestUserService;
import com.shrestaexclusive.platform.asset.AssetService;
import com.shrestaexclusive.platform.asset.InvalidAssetRequestException;

@Service
public class AdminChangeRequestService {

    private final AdminChangeRequestRepository repository;
    private final AdminChangeRequestApplier applier;
    private final AssetService assetService;
    private final AdminTestUserService testUserService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminChangeRequestService(AdminChangeRequestRepository repository, AdminChangeRequestApplier applier, AssetService assetService, AdminTestUserService testUserService) {
        this.repository = repository;
        this.applier = applier;
        this.assetService = assetService;
        this.testUserService = testUserService;
    }

    @Transactional
    public AdminChangeRequestResponse create(String submittedByRole, String actor, AdminChangeRequestCreateRequest request) {
        validateMediaRequest(actor, request);
        return repository.create(newRequestKey(), submittedByRole, request);
    }

    private void validateMediaRequest(String actor, AdminChangeRequestCreateRequest request) {
        submitProductMediaReservation(actor, request);
        validateDisplayUpload(actor, request);
        validateProductMediaLink(request);
        validateTestUserRequest(request);
    }

    private void validateTestUserRequest(AdminChangeRequestCreateRequest request) {
        if (!"test-user-management".equals(request.requestType())) {
            return;
        }
        if (!"customer_accounts".equals(request.entityType())) {
            throw new IllegalArgumentException("Test user management must target customer_accounts");
        }
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        if (payload.keySet().stream().anyMatch("otp"::equalsIgnoreCase)) {
            throw new IllegalArgumentException("OTP is minted by the platform at approval and must not be submitted");
        }
        switch (request.action()) {
            case "CREATE" -> testUserService.validateCreateSubmit(request.entityKey(), payload);
            case "DELETE" -> testUserService.validateDeleteSubmit(request.entityKey(), payload);
            default -> throw new IllegalArgumentException("test-user-management supports CREATE and DELETE only");
        }
    }

    private void validateProductMediaLink(AdminChangeRequestCreateRequest request) {
        if (!"storefront-product-media-link".equals(request.requestType())) {
            return;
        }
        if (!"UPDATE".equals(request.action()) || !"storefront_home_items".equals(request.entityType())) {
            throw new IllegalArgumentException("Product media links must update a storefront home item");
        }
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        String assetKey = requiredText(payload, "assetKey");
        StorefrontProductMediaSlot slot = StorefrontProductMediaSlot.parse(requiredText(payload, "slot"));
        rejectConflictingPendingMediaLink(request, slot);
        assetService.validateStorefrontProductMediaLink(request.entityKey(), assetKey, slot.targetMediaType());
    }

    private void rejectConflictingPendingMediaLink(AdminChangeRequestCreateRequest request, StorefrontProductMediaSlot slot) {
        AdminChangeRequestResponse existing = repository
                .findFirstPending(request.entityKey(), request.requestType())
                .orElse(null);
        if (existing == null || existing.payload() == null) {
            return;
        }
        String existingSlot = asText(existing.payload(), "slot");
        if (existingSlot != null && !slot.name().equalsIgnoreCase(existingSlot.trim())) {
            throw new InvalidAssetRequestException(
                    "A pending media link request already exists for this product (slot " + existingSlot
                            + "). Approve or reject it before submitting another.");
        }
    }

    private void validateDisplayUpload(String actor, AdminChangeRequestCreateRequest request) {
        if (!List.of("storefront-display-media", "storefront-display-image", "storefront-display-video")
            .contains(request.requestType())) {
            return;
        }
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        String imageAssetKey = asText(payload, "imageAssetKey");
        String videoAssetKey = asText(payload, "videoAssetKey");
        String imageMediaId = asText(payload, "imageMediaId");
        String videoMediaId = asText(payload, "videoMediaId");
        if (!"UPDATE".equals(request.action()) || !"storefront_home_item".equals(request.entityType())) {
            throw new IllegalArgumentException("Display media changes must update a storefront home item");
        }
        if ((imageAssetKey == null) == (videoAssetKey == null)) {
            throw new IllegalArgumentException("Exactly one display image or video asset is required");
        }
        if (videoAssetKey != null && videoMediaId == null) {
            throw new IllegalArgumentException("A verified display video upload is required");
        }
        if (imageMediaId != null && !"brand-shresta-exclusive".equals(request.entityKey())) {
            throw new IllegalArgumentException("Only the brand logo supports a separate display image upload");
        }
        if (imageMediaId != null) {
            assetService.validateDisplayUpload(imageMediaId, imageAssetKey, "DISPLAY_IMAGE", actor);
        }
        if (videoMediaId != null) {
            assetService.validateDisplayUpload(videoMediaId, videoAssetKey, "DISPLAY_VIDEO", actor);
        }
    }

    private void submitProductMediaReservation(String actor, AdminChangeRequestCreateRequest request) {
        if (!"storefront-product-create".equals(request.requestType())) {
            return;
        }
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        validateSubmittedProductCreate(payload);
        assetService.submitReservedProductMedia(
                request.entityKey(), actor,
                requiredText(payload, "mediaId"), requiredText(payload, "mediaAssetKey"),
                stringList(payload.get("galleryMediaIds")), stringList(payload.get("galleryAssetKeys")),
                asText(payload, "demoVideoMediaId"), asText(payload, "demoVideoAssetKey"));
    }

    private void validateSubmittedProductCreate(Map<String, Object> payload) {
        Map<String, Object> metadata = payload.get("metadata") instanceof Map<?, ?> raw
                ? raw.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().toString(), Map.Entry::getValue))
                : Map.of();
        for (String key : List.of("sku", "slug", "productType", "pricePaise", "stockQuantity")) {
            Object value = metadata.get(key);
            if (value == null || (value instanceof String text && text.isBlank())) {
                throw new IllegalArgumentException(key + " is required for product creation");
            }
        }
    }

    private static String requiredText(Map<String, Object> payload, String key) {
        String value = asText(payload, key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(item -> item instanceof String text ? text.trim() : "").toList();
    }

    @Transactional
    public AdminChangeRequestResponse createOrUpdatePending(
            String submittedByRole,
            String actor,
            AdminChangeRequestCreateRequest request
    ) {
        validateMediaRequest(actor, request);
        AdminChangeRequestResponse existing = repository
                .findFirstPending(request.entityKey(), request.requestType())
                .orElse(null);
        AdminChangeRequestResponse result = repository.upsertPending(newRequestKey(), submittedByRole, request);
        archiveSupersededPendingMedia(existing, request);
        return result;
    }

    private void archiveSupersededPendingMedia(
            AdminChangeRequestResponse existing,
            AdminChangeRequestCreateRequest replacement
    ) {
        if (existing == null || existing.payload() == null) {
            return;
        }
        Map<String, Object> oldPayload = existing.payload();
        Map<String, Object> newPayload = replacement.payload() == null ? Map.of() : replacement.payload();
        archiveSupersededUpload(oldPayload, newPayload, "imageMediaId", "imageAssetKey");
        archiveSupersededUpload(oldPayload, newPayload, "videoMediaId", "videoAssetKey");
        archiveSupersededUpload(oldPayload, newPayload, "mediaId", "newAssetKey");
        archiveSupersededUpload(oldPayload, newPayload, "mediaId", "galleryAssetKey");
        archiveSupersededUpload(oldPayload, newPayload, "mediaId", "demoVideoAssetKey");
    }

    private void archiveSupersededUpload(
            Map<String, Object> oldPayload,
            Map<String, Object> newPayload,
            String mediaIdKey,
            String assetKey
    ) {
        String oldMediaId = asText(oldPayload, mediaIdKey);
        String oldAssetKey = asText(oldPayload, assetKey);
        String newAssetKey = asText(newPayload, assetKey);
        if (oldMediaId != null && oldAssetKey != null && !oldAssetKey.equals(newAssetKey)) {
            assetService.archiveIfUnreferenced(oldAssetKey, newAssetKey);
        }
    }

    @Transactional(readOnly = true)
    public List<AdminChangeRequestResponse> list(String status) {
        return repository.list(status == null || status.isBlank() ? null : status.trim().toUpperCase());
    }

    @Transactional(readOnly = true)
    public AdminChangeRequestResponse get(String requestKey) {
        return repository.findByRequestKey(requestKey).orElseThrow(() -> new AdminChangeRequestNotFoundException(requestKey));
    }

    @Transactional
    public AdminChangeRequestResponse approve(String requestKey, String reviewerRole, AdminChangeRequestDecisionRequest request) {
        AdminChangeRequestResponse existing = get(requestKey);
        if (!"PENDING_REVIEW".equals(existing.status())) {
            return existing;
        }
        applier.apply(existing, request.reviewedBy());
        return repository.approve(requestKey, reviewerRole, request);
    }

    @Transactional
    public AdminChangeRequestResponse reject(String requestKey, String reviewerRole, AdminChangeRequestDecisionRequest request) {
        AdminChangeRequestResponse existing = get(requestKey);
        if (!"PENDING_REVIEW".equals(existing.status())) {
            return existing;
        }
        purgeUploadedAssetOnRejection(existing);
        return repository.reject(requestKey, reviewerRole, request);
    }

    /**
     * Cleans up the newly-uploaded S3 asset when an image/gallery change request is rejected,
     * so orphaned objects don't accumulate in storage.
     */
    private void purgeUploadedAssetOnRejection(AdminChangeRequestResponse request) {
        Map<String, Object> payload = request.payload();
        switch (request.requestType()) {
            case "storefront-product-create" -> assetService.archiveReservedProductMedia(request.entityKey());
            case "storefront-product-image" -> {
                String newAssetKey = asText(payload, "newAssetKey");
                if (newAssetKey != null && !newAssetKey.isBlank()) {
                    assetService.archive(newAssetKey);
                }
            }
            case "storefront-product-gallery" -> {
                String galleryAssetKey = asText(payload, "galleryAssetKey");
                if (galleryAssetKey != null && !galleryAssetKey.isBlank()) {
                    assetService.archive(galleryAssetKey);
                }
            }
            case "storefront-product-video" -> {
                String videoAssetKey = asText(payload, "demoVideoAssetKey");
                if (videoAssetKey != null && !videoAssetKey.isBlank()) {
                    assetService.archive(videoAssetKey);
                }
            }
            case "storefront-display-media", "storefront-display-image", "storefront-display-video" -> {
                String imageMediaId = asText(payload, "imageMediaId");
                String videoMediaId = asText(payload, "videoMediaId");
                if (imageMediaId != null) assetService.archiveDisplayUpload(imageMediaId, requiredText(payload, "imageAssetKey"), "DISPLAY_IMAGE");
                if (videoMediaId != null) assetService.archiveDisplayUpload(videoMediaId, requiredText(payload, "videoAssetKey"), "DISPLAY_VIDEO");
            }
            default -> { /* no asset cleanup needed for other request types */ }
        }
    }

    private static String asText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof String str && !str.isBlank() ? str.trim() : null;
    }

    private String newRequestKey() {
        byte[] bytes = new byte[8];
        secureRandom.nextBytes(bytes);
        return "acr-" + HexFormat.of().formatHex(bytes);
    }
}
