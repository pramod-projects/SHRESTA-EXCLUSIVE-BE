package com.shrestaexclusive.platform.admin.changes;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.admin.testusers.AdminTestUserService;
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
import com.shrestaexclusive.platform.email.configuration.NotificationConfigurationService;
import com.shrestaexclusive.platform.email.domain.NotificationType;
import com.shrestaexclusive.platform.order.RefundPolicyConfigurationService;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeItemCreateCommand;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeItemUpdateCommand;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeService;

@Service
public class AdminChangeRequestApplier {

    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final AssetService assetService;
    private final AdminCategoryService categoryService;
    private final StorefrontHomeService storefrontHomeService;
    private final NotificationConfigurationService notificationConfigurationService;
    private final RefundPolicyConfigurationService refundPolicyConfigurationService;
    private final AdminTestUserService testUserService;

    public AdminChangeRequestApplier(
            ObjectMapper objectMapper,
            AssetService assetService,
            AdminCategoryService categoryService,
            StorefrontHomeService storefrontHomeService,
            NotificationConfigurationService notificationConfigurationService,
            RefundPolicyConfigurationService refundPolicyConfigurationService,
            AdminTestUserService testUserService
    ) {
        this.objectMapper = objectMapper;
        this.assetService = assetService;
        this.categoryService = categoryService;
        this.storefrontHomeService = storefrontHomeService;
        this.notificationConfigurationService = notificationConfigurationService;
        this.refundPolicyConfigurationService = refundPolicyConfigurationService;
        this.testUserService = testUserService;
    }

    void apply(AdminChangeRequestResponse request, String reviewedBy) {
        String requestType = normalizedRequestType(request.requestType());
        switch (requestType) {
            case "asset-metadata" -> applyAssetMetadata(request);
            case "asset-removal" -> applyAssetRemoval(request);
            case "asset-bulk-category-assignment" -> applyAssetBulkAssignment(request);
            case "storefront-display-media", "storefront-display-image", "storefront-display-video" -> applyDisplayMedia(request);
            case "storefront-product-merchandising" -> applyProductMerchandising(request);
            case "storefront-product-image" -> applyProductImage(request);
            case "storefront-product-gallery" -> applyProductGallery(request);
            case "storefront-product-video" -> applyProductVideo(request);
            case "storefront-product-media-link" -> applyProductMediaLink(request);
            case "storefront-product-create" -> applyProductCreate(request);
                        case "category-merchandising" -> applyCategoryMerchandising(request);
            case "category-family" -> applyCategoryFamily(request);
            case "category-product-type" -> applyCategoryProductType(request);
            case "category-attribute" -> applyCategoryAttribute(request);
            case "category-filter" -> applyCategoryFilter(request);
            case "category-tax" -> applyCategoryTax(request);
            case "category-styling" -> applyCategoryStyling(request);
            case "notification-configuration", "configuration-notification" -> applyNotificationConfiguration(request, reviewedBy);
            case "configuration-refund-policy" -> applyRefundPolicyConfiguration(request, reviewedBy);
            case "test-user-management" -> applyTestUserManagement(request, reviewedBy);
            default -> throw new UnsupportedAdminChangeRequestException(request.requestKey(), request.requestType(), request.action());
        }
    }

    private void applyTestUserManagement(AdminChangeRequestResponse request, String reviewedBy) {
        switch (request.action()) {
            case "CREATE" -> testUserService.applyCreate(request, reviewedBy);
            case "DELETE" -> testUserService.applyDelete(request);
            default -> throw unsupported(request);
        }
    }

    private void applyNotificationConfiguration(AdminChangeRequestResponse request, String reviewedBy) {
        requireAction(request, "UPDATE");
        Map<String, Object> values = payload(request);
        Object enabled = values.get("enabled");
        if (!(enabled instanceof Boolean enabledValue)) throw new IllegalArgumentException("enabled is required");
        String reason = text(values, "reason");
        notificationConfigurationService.update(NotificationType.valueOf(request.entityKey().trim().toUpperCase()),
            enabledValue, reviewedBy, reason);
    }

    private void applyRefundPolicyConfiguration(AdminChangeRequestResponse request, String reviewedBy) {
        requireAction(request, "UPDATE");
        Map<String, Object> values = payload(request);
        Object eligibilityDays = values.get("eligibilityDays");
        if (!(eligibilityDays instanceof Number days)) {
            throw new IllegalArgumentException("eligibilityDays is required");
        }
        refundPolicyConfigurationService.update(days.intValue(), reviewedBy, requiredText(values, "reason", null));
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

    private void applyDisplayMedia(AdminChangeRequestResponse request) {
        requireAction(request, "UPDATE");
        Map<String, Object> payload = payload(request);
        String imageMediaId = text(payload, "imageMediaId");
        String videoMediaId = text(payload, "videoMediaId");
        if (imageMediaId != null) {
            assetService.validateDisplayUpload(imageMediaId, requiredText(payload, "imageAssetKey", null), "DISPLAY_IMAGE");
        }
        if (videoMediaId != null) {
            assetService.validateDisplayUpload(videoMediaId, requiredText(payload, "videoAssetKey", null), "DISPLAY_VIDEO");
        }
        String imageAssetKey = text(payload, "imageAssetKey");
        String videoAssetKey = text(payload, "videoAssetKey");
        String replacedAssetKey = imageAssetKey != null
                ? storefrontHomeService.currentItemImageAssetKey(request.entityKey())
                : storefrontHomeService.currentItemVideoAssetKey(request.entityKey());
        storefrontHomeService.updateDisplayMedia(
                request.entityKey(),
                imageAssetKey,
                videoAssetKey
        );
        assetService.archiveIfUnreferenced(replacedAssetKey, imageAssetKey != null ? imageAssetKey : videoAssetKey);
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
        if (metadata != null) {
            validateProductMetadata(metadata);
            categoryService.validateProductClassification(
                requiredText(payload, "familyKey", null),
                requiredText(metadata, "productType", null)
            );
        }
        List<String> galleryAssetKeys = stringList(payload.get("galleryAssetKeys"));
        String demoVideoAssetKey = text(payload, "demoVideoAssetKey");
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
                text(payload, "mediaAssetKey"),
                galleryAssetKeys,
                demoVideoAssetKey
        ));
    }

    private void applyProductImage(AdminChangeRequestResponse request) {
        requireAction(request, "UPDATE");
        Map<String, Object> payload = payload(request);
        String newAssetKey = requiredText(payload, "newAssetKey", null);
        String replacedAssetKey = storefrontHomeService.currentItemImageAssetKey(request.entityKey());
        storefrontHomeService.updateItem(new StorefrontHomeItemUpdateCommand(
                request.entityKey(),
            null, null, null, null, null, null, null, null, null,
            newAssetKey,
                null, null
        ));
        assetService.archiveIfUnreferenced(replacedAssetKey, newAssetKey);
    }

    private void applyProductGallery(AdminChangeRequestResponse request) {
        requireAction(request, "UPDATE");
        Map<String, Object> payload = payload(request);
        // entityKey = "productKey:gallery:N"
        String productKey = firstEntityPart(request.entityKey());
        Integer slot = integer(payload, "gallerySlot");
        String assetKey = text(payload, "galleryAssetKey");
        if (productKey != null && slot != null && slot >= 1 && slot <= 4) {
            String replacedAssetKey = storefrontHomeService.currentGalleryAssetKey(productKey, slot);
            storefrontHomeService.updateItemGallerySlot(productKey, slot, assetKey != null ? assetKey : "");
            assetService.archiveIfUnreferenced(replacedAssetKey, assetKey);
        }
    }

    private void applyProductVideo(AdminChangeRequestResponse request) {
        requireAction(request, "UPDATE");
        Map<String, Object> payload = payload(request);
        String demoVideoAssetKey = text(payload, "demoVideoAssetKey");
        String replacedAssetKey = storefrontHomeService.currentItemVideoAssetKey(request.entityKey());
        storefrontHomeService.updateItem(new StorefrontHomeItemUpdateCommand(
                request.entityKey(),
                null, null, null, null, null, null, null, null, null,
                null,
                null, demoVideoAssetKey != null ? demoVideoAssetKey : ""
        ));
        assetService.archiveIfUnreferenced(replacedAssetKey, demoVideoAssetKey);
    }

    private void applyProductMediaLink(AdminChangeRequestResponse request) {
        requireAction(request, "UPDATE");
        if (!"storefront_home_items".equals(request.entityType())) {
            throw unsupported(request);
        }
        Map<String, Object> payload = payload(request);
        String assetKey = requiredText(payload, "assetKey", null);
        StorefrontProductMediaSlot slot = StorefrontProductMediaSlot.parse(requiredText(payload, "slot", null));
        String itemKey = request.entityKey();
        assetService.validateStorefrontProductMediaLink(itemKey, assetKey, slot.targetMediaType());
        assetService.reassignStorefrontProductMedia(itemKey, assetKey, slot.targetMediaType());
        String replacedAssetKey;
        switch (slot) {
            case PRIMARY -> {
                replacedAssetKey = storefrontHomeService.currentItemImageAssetKey(itemKey);
                storefrontHomeService.updateItem(new StorefrontHomeItemUpdateCommand(
                        itemKey,
                        null, null, null, null, null, null, null, null, null,
                        assetKey,
                        null, null
                ));
            }
            case VIDEO -> {
                replacedAssetKey = storefrontHomeService.currentItemVideoAssetKey(itemKey);
                storefrontHomeService.updateItem(new StorefrontHomeItemUpdateCommand(
                        itemKey,
                        null, null, null, null, null, null, null, null, null,
                        null,
                        null, assetKey
                ));
            }
            default -> {
                int gallerySlot = slot.gallerySlot();
                replacedAssetKey = storefrontHomeService.currentGalleryAssetKey(itemKey, gallerySlot);
                storefrontHomeService.updateItemGallerySlot(itemKey, gallerySlot, assetKey);
            }
        }
        assetService.archiveIfUnreferenced(replacedAssetKey, assetKey);
    }

    private void applyProductCreate(AdminChangeRequestResponse request) {
        requireAction(request, "CREATE");
        Map<String, Object> payload = payload(request);
        Map<String, Object> metadata = new HashMap<>(objectMap(payload.get("metadata")));
        if (!metadata.containsKey("longDescription") && payload.containsKey("longDescription")) {
            metadata.put("longDescription", payload.get("longDescription"));
        }
        validateProductMetadata(metadata);
        categoryService.validateProductClassification(
            requiredText(payload, "familyKey", null),
            requiredText(metadata, "productType", null)
        );
        String sectionKey = text(payload, "sectionKey");
        Integer sortOrder = integer(payload, "sortOrder");
        Boolean featured = bool(payload, "featured");
        List<String> galleryAssetKeys = stringList(payload.get("galleryAssetKeys"));
        assetService.validateSubmittedProductMedia(
            request.entityKey(),
            requiredText(payload, "mediaId", null),
            requiredText(payload, "mediaAssetKey", null),
            stringList(payload.get("galleryMediaIds")),
            galleryAssetKeys,
            text(payload, "demoVideoMediaId"),
            text(payload, "demoVideoAssetKey")
        );
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
                requiredText(payload, "mediaAssetKey", null),
                galleryAssetKeys,
                text(payload, "demoVideoAssetKey")
        ));
        assetService.consumeProductMediaReservation(request.entityKey());
    }

    private void applyCategoryMerchandising(AdminChangeRequestResponse request) {
        requireAction(request, "UPDATE");
        Map<String, Object> values = payload(request);
        categoryService.updateFamilyMerchandising(
                request.entityKey(),
                objectList(values.get("merchandisingTags")),
                objectList(values.get("colorFilters"))
        );
    }

    private void validateProductMetadata(Map<String, Object> metadata) {
        requiredText(metadata, "sku", null);
        String slug = requiredText(metadata, "slug", null);
        requiredText(metadata, "productType", null);
        if (!slug.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$")) {
            throw new IllegalArgumentException("slug must contain lowercase letters, numbers, and single hyphens");
        }
        long pricePaise = requiredLong(metadata, "pricePaise");
        long compareAtPricePaise = optionalLong(metadata, "compareAtPricePaise", 0);
        double rating = optionalDouble(metadata, "rating", 0);
        int reviewCount = optionalInteger(metadata, "reviewCount", 0);
        int stockQuantity = requiredInteger(metadata, "stockQuantity");
        if (pricePaise <= 0 || compareAtPricePaise < 0 || (compareAtPricePaise > 0 && compareAtPricePaise < pricePaise)) {
            throw new IllegalArgumentException("product pricing is invalid");
        }
        if (!Double.isFinite(rating) || rating < 0 || rating > 5 || reviewCount < 0 || stockQuantity < 0) {
            throw new IllegalArgumentException("product rating, review count, or stock quantity is invalid");
        }
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
            return exactInteger(number, key);
        }
        if (value instanceof String && !value.toString().isBlank()) {
            return Integer.valueOf(value.toString());
        }
        return null;
    }

    private int requiredInteger(Map<String, Object> source, String key) {
        Integer value = integer(source, key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required for approved admin change request");
        }
        return value;
    }

    private int optionalInteger(Map<String, Object> source, String key, int fallback) {
        Integer value = integer(source, key);
        return value != null ? value : fallback;
    }

    private long requiredLong(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return exactLong(number, key);
        }
        throw new IllegalArgumentException(key + " is required for approved admin change request");
    }

    private long optionalLong(Map<String, Object> source, String key, long fallback) {
        Object value = source.get(key);
        return value instanceof Number number ? exactLong(number, key) : fallback;
    }

    private double optionalDouble(Map<String, Object> source, String key, double fallback) {
        Object value = source.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private int exactInteger(Number number, String key) {
        try {
            return new BigDecimal(number.toString()).toBigIntegerExact().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an exact 32-bit integer", exception);
        }
    }

    private long exactLong(Number number, String key) {
        try {
            BigInteger integer = new BigDecimal(number.toString()).toBigIntegerExact();
            return integer.longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an exact 64-bit integer", exception);
        }
    }

    private Boolean bool(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String && !value.toString().isBlank()) {
            return Boolean.valueOf(value.toString());
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

    private List<Map<String, Object>> objectList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected an array in approved admin change request");
        }
        return list.stream().map(this::objectMap).toList();
    }
}
