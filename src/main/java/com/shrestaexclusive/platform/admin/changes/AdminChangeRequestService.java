package com.shrestaexclusive.platform.admin.changes;

import com.shrestaexclusive.platform.asset.AssetService;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminChangeRequestService {

    private final AdminChangeRequestRepository repository;
    private final AdminChangeRequestApplier applier;
    private final AssetService assetService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminChangeRequestService(AdminChangeRequestRepository repository, AdminChangeRequestApplier applier, AssetService assetService) {
        this.repository = repository;
        this.applier = applier;
        this.assetService = assetService;
    }

    @Transactional
    public AdminChangeRequestResponse create(String submittedByRole, AdminChangeRequestCreateRequest request) {
        return repository.create(newRequestKey(), submittedByRole, request);
    }

    @Transactional
    public AdminChangeRequestResponse createOrUpdatePending(String submittedByRole, AdminChangeRequestCreateRequest request) {
        return repository.upsertPending(newRequestKey(), submittedByRole, request);
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
        applier.apply(existing);
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
