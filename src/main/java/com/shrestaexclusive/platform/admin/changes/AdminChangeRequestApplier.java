package com.shrestaexclusive.platform.admin.changes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.asset.AssetMetadataUpdateRequest;
import com.shrestaexclusive.platform.asset.AssetService;
import com.shrestaexclusive.platform.asset.BulkCategoryAssignmentRequest;
import com.shrestaexclusive.platform.category.admin.AdminCategoryService;
import com.shrestaexclusive.platform.category.admin.CategoryAttributeMutationRequest;
import com.shrestaexclusive.platform.category.admin.CategoryFamilyMutationRequest;
import com.shrestaexclusive.platform.category.admin.CategoryFilterMutationRequest;
import com.shrestaexclusive.platform.category.admin.CategoryProductTypeMutationRequest;
import com.shrestaexclusive.platform.category.admin.CategoryStylingMutationRequest;
import com.shrestaexclusive.platform.category.admin.CategoryTaxMutationRequest;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeItemCreateCommand;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeItemUpdateCommand;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class AdminChangeRequestApplier {

    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final AssetService assetService;
    private final AdminCategoryService categoryService;
    private final StorefrontHomeService storefrontHomeService;

    AdminChangeRequestApplier(
            ObjectMapper objectMapper,
            AssetService assetService,
            AdminCategoryService categoryService,
            StorefrontHomeService storefrontHomeService
    ) {
        this.objectMapper = objectMapper;
        this.assetService = assetService;
        this.categoryService = categoryService;
        this.storefrontHomeService = storefrontHomeService;
    }

    void apply(AdminChangeRequestResponse request) {
        String requestType = normalizedRequestType(request.requestType());
        switch (requestType) {
            case "asset-metadata" -> applyAssetMetadata(request);
            case "asset-removal" -> applyAssetRemoval(request);
            case "asset-bulk-category-assignment" -> applyAssetBulkAssignment(request);
            case "storefront-product-merchandising" -> applyProductMerchandising(request);
            case "storefront-product-image" -> applyProductImage(request);
            case "storefront-product-gallery" -> applyProductGallery(request);
            case "storefront-product-video" -> applyProductVideo(request);
            case "storefront-product-create" -> applyProductCreate(request);
            case "category-family" -> applyCategoryFamily(request);
            case "category-product-type" -> applyCategoryProductType(request);
            case "category-attribute" -> applyCategoryAttribute(request);
            case "category-filter" -> applyCategoryFilter(request);
            case "category-tax" -> applyCategoryTax(request);
            case "category-styling" -> applyCategoryStyling(request);
            default -> throw new UnsupportedAdminChangeRequestException(request.requestKey(), request.requestType(), request.action());
        }
    }

    private void applyAssetMetadata(AdminChangeRequestResponse request) {
        requireAction(request, "UPDATE");
        assetService.updateMetadata(request.entityKey(), convert(request.payload(), AssetMetadataUpdateRequest.class));
    }

    private void applyAssetRemoval(AdminChangeRequestResponse request) {
        switch (request.action()) {
            case "ARCHIVE" -> assetService.archive(request.entityKey());
            case "DELETE" -> assetService.deletePermanently(request.entityKey());
            default -> throw unsupported(request);
        }
    }

    private void applyAssetBulkAssignment(AdminChangeRequestResponse request) {
        requireAction(request, "UPDATE");
        assetService.bulkAssignCategory(convert(request.payload(), BulkCategoryAssignmentRequest.class));
    }

    private void applyProductMerchandising(AdminChangeRequestResponse request) {
        requireAction(request, "UPDATE");
        Map<String, Object> payload = payload(request);
        // Only pass metadata when the key is explicitly present in the payload.
        // objectMap(null) returns Map.of() which serialises to "{}" and would
        // overwrite existing metadata via COALESCE if passed unconditionally.
        Map<String, Object> metadata = payload.containsKey("metadata")
                ? objectMap(payload.get("metadata"))
                : null;
        Map<String, Object> media = objectMap(payload.get("media"));
        List<String> galleryAssetKeys = stringList(payload.get("galleryAssetKeys"));
        String demoVideoUrl = text(payload, "demoVideoUrl");
        storefrontHomeService.updateItem(new StorefrontHomeItemUpdateCommand(
                request.entityKey(),
                text(payload, "familyKey"),
                text(payload, "title"),
                text(payload, "subtitle"),
                text(payload, "description"),
                text(payload, "ctaLabel"),
                text(payload, "ctaHref"),
                integer(payload, "sortOrder"),
                bool(payload, "featured"),
                metadata,
                text(media, "assetUrl"),
                text(media, "altText"),
                integer(media, "widthPx"),
                integer(media, "heightPx"),
                text(media, "deliveryMode"),
                galleryAssetKeys,
                demoVideoUrl
        ));
    }

    /**
     * Apply a primary-image-only change. Only the media fields are updated;
     * all text, metadata, gallery, and video fields are left untouched.
     * If {@code oldAssetKey} is present in the payload, that asset is archived
     * atomically so no separate asset-removal review item is needed.
     */
    private void applyProductImage(AdminChangeRequestResponse request) {
        requireAction(request, "UPDATE");
        Map<String, Object> payload = payload(request);
        Map<String, Object> media = objectMap(payload.get("media"));
        storefrontHomeService.updateItem(new StorefrontHomeItemUpdateCommand(
                request.entityKey(),
                null, null, null, null, null, null, null, null, null,
                text(media, "assetUrl"),
                text(media, "altText"),
                integer(media, "widthPx"),
                integer(media, "heightPx"),
                text(media, "deliveryMode"),
                null, null
        ));
        String oldAssetKey = text(payload, "oldAssetKey");
        if (oldAssetKey != null && !oldAssetKey.isBlank()) {
            assetService.archive(oldAssetKey);
        }
    }

    /**
     * Apply a single gallery-slot change.
     * entityKey format: {@code "productKey:gallery:N"} (1-based slot).
     * Only the target slot is touched; all other slots and fields are untouched.
     * If {@code oldAssetKey} is present in the payload, that asset is archived
     * atomically so no separate asset-removal review item is needed.
     */
    private void applyProductGallery(AdminChangeRequestResponse request) {
        requireAction(request, "UPDATE");
        Map<String, Object> payload = payload(request);
        // entityKey = "productKey:gallery:N"
        String productKey = firstEntityPart(request.entityKey());
        Integer slot = integer(payload, "gallerySlot");
        String assetKey = text(payload, "galleryAssetKey");
        if (productKey != null && slot != null && slot >= 1 && slot <= 4) {
            storefrontHomeService.updateItemGallerySlot(productKey, slot, assetKey != null ? assetKey : "");
        }
        String oldAssetKey = text(payload, "oldAssetKey");
        if (oldAssetKey != null && !oldAssetKey.isBlank()) {
            assetService.archive(oldAssetKey);
        }
    }

    /**
     * Apply a video-only change. Only demo_video_url is updated;
     * all text, metadata, media, and gallery fields are left untouched.
     */
    private void applyProductVideo(AdminChangeRequestResponse request) {
        requireAction(request, "UPDATE");
        Map<String, Object> payload = payload(request);
        String demoVideoUrl = text(payload, "demoVideoUrl");
        // Pass "" to clear, actual URL to set, null would skip — default to "" if key absent
        storefrontHomeService.updateItem(new StorefrontHomeItemUpdateCommand(
                request.entityKey(),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                null, demoVideoUrl != null ? demoVideoUrl : ""
        ));
    }

    private void applyProductCreate(AdminChangeRequestResponse request) {
        requireAction(request, "CREATE");
        Map<String, Object> payload = payload(request);
        Map<String, Object> metadata = objectMap(payload.get("metadata"));
        String sectionKey = text(payload, "sectionKey");
        Integer sortOrder = integer(payload, "sortOrder");
        Boolean featured = bool(payload, "featured");
        storefrontHomeService.createItem(new StorefrontHomeItemCreateCommand(
                sectionKey != null ? sectionKey : "bestsellers",
                request.entityKey(),
                text(payload, "familyKey"),
                requiredText(payload, "title", null),
                text(payload, "subtitle"),
                text(payload, "description"),
                text(payload, "ctaLabel"),
                text(payload, "ctaHref"),
                sortOrder != null ? sortOrder : 0,
                featured != null ? featured : false,
                metadata,
                text(payload, "mediaAssetKey"),
                stringList(payload.get("galleryAssetKeys")),
                text(payload, "demoVideoUrl")
        ));
    }

    private void applyCategoryFamily(AdminChangeRequestResponse request) {
        Map<String, Object> payload = payload(request);
        switch (request.action()) {
            case "CREATE" -> categoryService.createFamily(convert(payload, CategoryFamilyMutationRequest.class));
            case "UPDATE" -> categoryService.updateFamily(request.entityKey(), convert(payload, CategoryFamilyMutationRequest.class));
            case "ARCHIVE" -> categoryService.archiveFamily(requiredText(payload, "familyKey", request.entityKey()));
            case "DELETE" -> categoryService.deleteFamily(requiredText(payload, "familyKey", request.entityKey()));
            default -> throw unsupported(request);
        }
    }

    private void applyCategoryProductType(AdminChangeRequestResponse request) {
        Map<String, Object> payload = payload(request);
        String familyKey = requiredText(payload, "familyKey", firstEntityPart(request.entityKey()));
        String typeKey = requiredText(payload, "typeKey", secondEntityPart(request.entityKey()));
        switch (request.action()) {
            case "CREATE" -> categoryService.createProductType(familyKey, convert(payload, CategoryProductTypeMutationRequest.class));
            case "UPDATE" -> categoryService.updateProductType(familyKey, typeKey, convert(payload, CategoryProductTypeMutationRequest.class));
            case "ARCHIVE" -> categoryService.archiveProductType(familyKey, typeKey);
            case "DELETE" -> categoryService.deleteProductType(familyKey, typeKey);
            default -> throw unsupported(request);
        }
    }

    private void applyCategoryAttribute(AdminChangeRequestResponse request) {
        Map<String, Object> payload = payload(request);
        String familyKey = requiredText(payload, "familyKey", firstEntityPart(request.entityKey()));
        String attributeKey = requiredText(payload, "attributeKey", secondEntityPart(request.entityKey()));
        switch (request.action()) {
            case "CREATE" -> categoryService.createAttribute(familyKey, convert(payload, CategoryAttributeMutationRequest.class));
            case "UPDATE" -> categoryService.updateAttribute(familyKey, attributeKey, convert(payload, CategoryAttributeMutationRequest.class));
            case "ARCHIVE" -> categoryService.archiveAttribute(familyKey, attributeKey);
            case "DELETE" -> categoryService.deleteAttribute(familyKey, attributeKey);
            default -> throw unsupported(request);
        }
    }

    private void applyCategoryFilter(AdminChangeRequestResponse request) {
        Map<String, Object> payload = payload(request);
        String familyKey = requiredText(payload, "familyKey", firstEntityPart(request.entityKey()));
        String filterKey = requiredText(payload, "filterKey", secondEntityPart(request.entityKey()));
        switch (request.action()) {
            case "CREATE" -> categoryService.createFilter(familyKey, convert(payload, CategoryFilterMutationRequest.class));
            case "UPDATE" -> categoryService.updateFilter(familyKey, filterKey, convert(payload, CategoryFilterMutationRequest.class));
            case "ARCHIVE" -> categoryService.archiveFilter(familyKey, filterKey);
            case "DELETE" -> categoryService.deleteFilter(familyKey, filterKey);
            default -> throw unsupported(request);
        }
    }

    private void applyCategoryTax(AdminChangeRequestResponse request) {
        Map<String, Object> payload = payload(request);
        String familyKey = requiredText(payload, "familyKey", firstEntityPart(request.entityKey()));
        String hsnCode = requiredText(payload, "targetHsnCode", requiredText(payload, "hsnCode", secondEntityPart(request.entityKey())));
        LocalDate effectiveFrom = LocalDate.parse(requiredText(payload, "targetEffectiveFrom", requiredText(payload, "effectiveFrom", thirdEntityPart(request.entityKey()))));
        switch (request.action()) {
            case "CREATE" -> categoryService.createTax(familyKey, convert(payload, CategoryTaxMutationRequest.class));
            case "UPDATE" -> categoryService.updateTax(familyKey, hsnCode, effectiveFrom, convert(payload, CategoryTaxMutationRequest.class));
            case "ARCHIVE" -> categoryService.archiveTax(familyKey, hsnCode, effectiveFrom);
            case "DELETE" -> categoryService.deleteTax(familyKey, hsnCode, effectiveFrom);
            default -> throw unsupported(request);
        }
    }

    private void applyCategoryStyling(AdminChangeRequestResponse request) {
        Map<String, Object> payload = payload(request);
        String familyKey = requiredText(payload, "familyKey", firstEntityPart(request.entityKey()));
        String occasionKey = requiredText(payload, "occasionKey", secondEntityPart(request.entityKey()));
        switch (request.action()) {
            case "CREATE" -> categoryService.createStyling(familyKey, convert(payload, CategoryStylingMutationRequest.class));
            case "UPDATE" -> categoryService.updateStyling(familyKey, occasionKey, convert(payload, CategoryStylingMutationRequest.class));
            case "ARCHIVE" -> categoryService.archiveStyling(familyKey, occasionKey);
            case "DELETE" -> categoryService.deleteStyling(familyKey, occasionKey);
            default -> throw unsupported(request);
        }
    }

    private void requireAction(AdminChangeRequestResponse request, String expectedAction) {
        if (!expectedAction.equals(request.action())) {
            throw unsupported(request);
        }
    }

    private UnsupportedAdminChangeRequestException unsupported(AdminChangeRequestResponse request) {
        return new UnsupportedAdminChangeRequestException(request.requestKey(), request.requestType(), request.action());
    }

    private String normalizedRequestType(String requestType) {
        if (requestType == null) {
            return "";
        }
        if (requestType.startsWith("category-") && requestType.endsWith("-removal")) {
            return requestType.replaceFirst("-removal$", "");
        }
        return requestType;
    }

    private Map<String, Object> payload(AdminChangeRequestResponse request) {
        return request.payload() == null ? Map.of() : request.payload();
    }

    private Map<String, Object> objectMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        return objectMapper.convertValue(value, STRING_OBJECT_MAP);
    }

    private <T> T convert(Object value, Class<T> targetType) {
        return objectMapper.convertValue(value == null ? Map.of() : value, targetType);
    }

    private String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : value.toString();
    }

    private String requiredText(Map<String, Object> source, String key, String fallback) {
        String value = text(source, key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        throw new IllegalArgumentException(key + " is required for approved admin change request");
    }

    private Integer integer(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return null;
    }

    private Boolean bool(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private String firstEntityPart(String entityKey) {
        return entityPart(entityKey, 0);
    }

    private String secondEntityPart(String entityKey) {
        return entityPart(entityKey, 1);
    }

    private String thirdEntityPart(String entityKey) {
        return entityPart(entityKey, 2);
    }

    private String entityPart(String entityKey, int index) {
        if (entityKey == null) {
            return null;
        }
        String[] parts = entityKey.split(":");
        return parts.length > index ? parts[index] : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(item -> item == null ? null : item.toString()).toList();
        }
        return null;
    }
}
