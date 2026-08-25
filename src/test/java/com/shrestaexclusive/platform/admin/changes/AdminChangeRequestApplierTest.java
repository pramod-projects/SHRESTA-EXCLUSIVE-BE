package com.shrestaexclusive.platform.admin.changes;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shrestaexclusive.platform.admin.testusers.AdminTestUserService;
import com.shrestaexclusive.platform.asset.AssetService;
import com.shrestaexclusive.platform.category.admin.AdminCategoryService;
import com.shrestaexclusive.platform.email.configuration.NotificationConfigurationService;
import com.shrestaexclusive.platform.email.domain.NotificationType;
import com.shrestaexclusive.platform.order.RefundPolicyConfigurationService;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeItemCreateCommand;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeItemUpdateCommand;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeService;

class AdminChangeRequestApplierTest {

    private final AssetService assetService = mock(AssetService.class);
    private final AdminCategoryService categoryService = mock(AdminCategoryService.class);
    private final StorefrontHomeService storefrontHomeService = mock(StorefrontHomeService.class);
        private final NotificationConfigurationService notificationConfigurationService = mock(NotificationConfigurationService.class);
        private final RefundPolicyConfigurationService refundPolicyConfigurationService = mock(RefundPolicyConfigurationService.class);
        private final AdminTestUserService testUserService = mock(AdminTestUserService.class);
    private final AdminChangeRequestApplier applier = new AdminChangeRequestApplier(
            new ObjectMapper().registerModule(new JavaTimeModule()),
            assetService,
            categoryService,
            storefrontHomeService,
            notificationConfigurationService,
            refundPolicyConfigurationService,
            testUserService
    );

    @Test
    void appliesPermanentAssetDeleteRequests() {
        applier.apply(response(
                "asset-removal",
                "media_asset",
                "saree-silk-0001",
                "DELETE",
                Map.of("assetKey", "saree-silk-0001")
        ), "reviewer@shresta.local");

        verify(assetService).deletePermanently("saree-silk-0001");
    }

    @Test
    void appliesCategoryRemovalRequests() {
        applier.apply(response(
                "category-attribute-removal",
                "category_attribute_config",
                "silk_saree:occasion",
                "DELETE",
                Map.of("familyKey", "silk_saree", "attributeKey", "occasion")
        ), "reviewer@shresta.local");

        verify(categoryService).deleteAttribute("silk_saree", "occasion");
    }

    @Test
    void mapsMerchandisingProductPayloadToStorefrontCommand() {
        applier.apply(response(
                "storefront-product-merchandising",
                "storefront_home_item",
                "product-shresta-ad-0001",
                "UPDATE",
                Map.of(
                        "familyKey", "silk_saree",
                        "title", "Classic Silk Saree",
                        "subtitle", "Premium Weave",
                        "description", "Updated product card",
                        "ctaLabel", "View",
                        "ctaHref", "/products/classic-silk-saree",
                        "sortOrder", 10,
                        "featured", true,
                        "metadata", Map.of(
                                "sku", "SHRESTA-SILK-0001",
                                "slug", "classic-silk-saree",
                                "productType", "saree",
                                "stockQuantity", 10,
                                "pricePaise", 123000
                        ),
                        "mediaAssetKey", "media-550e8400e29b41d4a716446655440000"
                )
        ), "reviewer@shresta.local");

        ArgumentCaptor<StorefrontHomeItemUpdateCommand> captor = ArgumentCaptor.forClass(StorefrontHomeItemUpdateCommand.class);
        verify(storefrontHomeService).updateItem(captor.capture());
        StorefrontHomeItemUpdateCommand command = captor.getValue();
        assertThat(command.itemKey()).isEqualTo("product-shresta-ad-0001");
        assertThat(command.familyKey()).isEqualTo("silk_saree");
                assertThat(command.mediaAssetKey()).isEqualTo("media-550e8400e29b41d4a716446655440000");
        assertThat(command.metadata()).containsEntry("sku", "SHRESTA-SILK-0001");
    }

        @Test
        void rejectsApprovedProductPayloadWithoutCommerceIdentity() {
                assertThatThrownBy(() -> applier.apply(response(
                                "storefront-product-create",
                                "storefront_home_items",
                                "product-incomplete",
                                "CREATE",
                                Map.of("metadata", Map.of("pricePaise", 10000))
                ), "reviewer@shresta.local"))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("sku is required");
        }

        @Test
        void persistsLegacyRootLongDescriptionWhenCreatingProduct() {
                applier.apply(response(
                                "storefront-product-create",
                                "storefront_home_items",
                                "product-new",
                                "CREATE",
                                Map.of(
                                                "familyKey", "silk_saree",
                                                "title", "New Silk Saree",
                                                "longDescription", "Full product details",
                                                "mediaId", "550e8400-e29b-41d4-a716-446655440000",
                                                "mediaAssetKey", "media-new",
                                                "metadata", Map.of(
                                                                "sku", "NEW-SILK-001",
                                                                "slug", "new-silk-saree",
                                                                "productType", "saree",
                                                                "pricePaise", 10000,
                                                                "stockQuantity", 1
                                                )
                                )
                ), "reviewer@shresta.local");

                ArgumentCaptor<StorefrontHomeItemCreateCommand> captor =
                                ArgumentCaptor.forClass(StorefrontHomeItemCreateCommand.class);
                verify(storefrontHomeService).createItem(captor.capture());
                assertThat(captor.getValue().metadata())
                                .containsEntry("longDescription", "Full product details");
        }

        @Test
        void appliesCategoryMerchandisingAsScopedPatch() {
                var tags = java.util.List.<Map<String, Object>>of(Map.of("value", "PREMIUM", "label", "Premium", "icon", "Gem"));
                var colors = java.util.List.<Map<String, Object>>of(Map.of("value", "COLOR_RED", "label", "Red"));

                applier.apply(response(
                                "category-merchandising",
                                "category_family_config",
                                "silk_saree",
                                "UPDATE",
                                Map.of("merchandisingTags", tags, "colorFilters", colors)
                ), "reviewer@shresta.local");

                verify(categoryService).updateFamilyMerchandising("silk_saree", tags, colors);
        }

        @Test
        void mapsPrimaryImageApprovalToNewCanonicalAssetKey() {
                when(storefrontHomeService.currentItemImageAssetKey("product-shresta-ad-0001"))
                                .thenReturn("media-old");
                applier.apply(response(
                                "storefront-product-image",
                                "storefront_home_item",
                                "product-shresta-ad-0001",
                                "UPDATE",
                                Map.of(
                                                "newAssetKey", "media-new",
                                                "oldAssetKey", "media-old"
                                )
                ), "reviewer@shresta.local");

                ArgumentCaptor<StorefrontHomeItemUpdateCommand> captor = ArgumentCaptor.forClass(StorefrontHomeItemUpdateCommand.class);
                verify(storefrontHomeService).updateItem(captor.capture());
                assertThat(captor.getValue().mediaAssetKey()).isEqualTo("media-new");
                verify(assetService).archiveIfUnreferenced("media-old", "media-new");
        }

        @Test
        void replacesDisplayVideoAndCleansTheAuthoritativePreviousAsset() {
                String mediaId = "9de62ed4-b829-48f2-a140-f1f899322d6a";
                when(storefrontHomeService.currentItemVideoAssetKey("brand-shresta-exclusive"))
                                .thenReturn("media-old-video");

                applier.apply(response(
                                "storefront-display-media",
                                "storefront_home_item",
                                "brand-shresta-exclusive",
                                "UPDATE",
                                Map.of("videoAssetKey", "media-new-video", "videoMediaId", mediaId)
                ), "reviewer@shresta.local");

                verify(assetService).validateDisplayUpload(mediaId, "media-new-video", "DISPLAY_VIDEO");
                verify(storefrontHomeService).updateDisplayMedia(
                                "brand-shresta-exclusive", null, "media-new-video");
                verify(assetService).archiveIfUnreferenced("media-old-video", "media-new-video");
        }

    @Test
    void reassignsOwnershipBeforeLinkingPrimaryImage() {
        when(storefrontHomeService.currentItemImageAssetKey("product-one")).thenReturn("media-old");

        applier.apply(response(
                "storefront-product-media-link",
                "storefront_home_items",
                "product-one",
                "UPDATE",
                Map.of("assetKey", "media-new", "mediaId", "550e8400-e29b-41d4-a716-446655440000", "slot", "PRIMARY")
        ), "reviewer@shresta.local");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(assetService, storefrontHomeService);
        order.verify(assetService).validateStorefrontProductMediaLink("product-one", "media-new", "PRODUCT_IMAGE");
        order.verify(assetService).reassignStorefrontProductMedia("product-one", "media-new", "PRODUCT_IMAGE");
        order.verify(storefrontHomeService).updateItem(org.mockito.ArgumentMatchers.any());
        order.verify(assetService).archiveIfUnreferenced("media-old", "media-new");
    }

    @Test
    void linksGallerySlotAndArchivesReplacedAsset() {
        when(storefrontHomeService.currentGalleryAssetKey("product-one", 2)).thenReturn("media-old-gallery");

        applier.apply(response(
                "storefront-product-media-link",
                "storefront_home_items",
                "product-one",
                "UPDATE",
                Map.of("assetKey", "media-new", "mediaId", "550e8400-e29b-41d4-a716-446655440000", "slot", "GALLERY_2")
        ), "reviewer@shresta.local");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(assetService, storefrontHomeService);
        order.verify(assetService).reassignStorefrontProductMedia("product-one", "media-new", "PRODUCT_IMAGE");
        order.verify(storefrontHomeService).updateItemGallerySlot("product-one", 2, "media-new");
        order.verify(assetService).archiveIfUnreferenced("media-old-gallery", "media-new");
    }

    @Test
    void linksVideoSlotAsProductVideo() {
        when(storefrontHomeService.currentItemVideoAssetKey("product-one")).thenReturn("media-old-video");

        applier.apply(response(
                "storefront-product-media-link",
                "storefront_home_items",
                "product-one",
                "UPDATE",
                Map.of("assetKey", "media-new-video", "mediaId", "550e8400-e29b-41d4-a716-446655440000", "slot", "VIDEO")
        ), "reviewer@shresta.local");

        verify(assetService).reassignStorefrontProductMedia("product-one", "media-new-video", "PRODUCT_VIDEO");
        verify(storefrontHomeService).updateItem(org.mockito.ArgumentMatchers.argThat(
                command -> "product-one".equals(command.itemKey()) && "media-new-video".equals(command.demoVideoAssetKey())));
        verify(assetService).archiveIfUnreferenced("media-old-video", "media-new-video");
    }

    @Test
    void rejectsProductMediaLinkWithUnknownSlot() {
        assertThatThrownBy(() -> applier.apply(response(
                "storefront-product-media-link",
                "storefront_home_items",
                "product-one",
                "UPDATE",
                Map.of("assetKey", "media-new", "mediaId", "550e8400-e29b-41d4-a716-446655440000", "slot", "BANNER")
        ), "reviewer@shresta.local"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slot must be one of");
    }

    @Test
    void rejectsProductMediaLinkForWrongEntityType() {
        assertThatThrownBy(() -> applier.apply(response(
                "storefront-product-media-link",
                "storefront_home_item",
                "product-one",
                "UPDATE",
                Map.of("assetKey", "media-new", "mediaId", "550e8400-e29b-41d4-a716-446655440000", "slot", "PRIMARY")
        ), "reviewer@shresta.local"))
                .isInstanceOf(UnsupportedAdminChangeRequestException.class);
    }

    @Test
    void appliesNotificationConfigurationWithApprovalIdentityAndReason() {
        applier.apply(response(
                "notification-configuration",
                "notification-configuration",
                "ORDER_SHIPPED",
                "UPDATE",
                Map.of("enabled", false, "reason", "Temporarily pause shipment notices")
        ), "reviewer@shresta.local");

        verify(notificationConfigurationService).update(NotificationType.ORDER_SHIPPED, false,
                "reviewer@shresta.local", "Temporarily pause shipment notices");
    }

    @Test
    void appliesRefundPolicyConfigurationWithApprovalIdentityAndReason() {
        applier.apply(response(
                "configuration-refund-policy",
                "refund-policy-configuration",
                "CUSTOMER_REFUND",
                "UPDATE",
                Map.of("eligibilityDays", 7, "reason", "Extend the customer refund window")
        ), "reviewer@shresta.local");

        verify(refundPolicyConfigurationService).update(
                7, "reviewer@shresta.local", "Extend the customer refund window");
    }

    @Test
    void rejectsFractionalProductIntegerMetadata() {
        assertThatThrownBy(() -> applier.apply(response(
                "storefront-product-create",
                "storefront_home_items",
                "product-fractional-stock",
                "CREATE",
                Map.of(
                        "familyKey", "silk_saree",
                        "metadata", Map.of(
                                "sku", "FRACTIONAL_STOCK",
                                "slug", "fractional-stock",
                                "productType", "saree",
                                "pricePaise", 10000,
                                "stockQuantity", 0.5
                        )
                )
        ), "reviewer@shresta.local"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stockQuantity must be an exact 32-bit integer");
    }

    @Test
    void delegatesTestUserCreateToTestUserService() {
        AdminChangeRequestResponse request = response(
                "test-user-management",
                "customer_accounts",
                "qa.user@example.com",
                "CREATE",
                Map.of("displayName", "QA User", "email", "qa.user@example.com")
        );

        applier.apply(request, "approver@shresta.local");

        verify(testUserService).applyCreate(request, "approver@shresta.local");
    }

    @Test
    void delegatesTestUserDeleteToTestUserService() {
        AdminChangeRequestResponse request = response(
                "test-user-management",
                "customer_accounts",
                "550e8400-e29b-41d4-a716-446655440000",
                "DELETE",
                Map.of("customerId", "550e8400-e29b-41d4-a716-446655440000", "reason", "lane cleanup")
        );

        applier.apply(request, "approver@shresta.local");

        verify(testUserService).applyDelete(request);
    }

    @Test
    void rejectsUnsupportedTestUserAction() {
        assertThatThrownBy(() -> applier.apply(response(
                "test-user-management",
                "customer_accounts",
                "qa.user@example.com",
                "UPDATE",
                Map.of("displayName", "QA User", "email", "qa.user@example.com")
        ), "approver@shresta.local"))
                .isInstanceOf(UnsupportedAdminChangeRequestException.class);
    }

    private AdminChangeRequestResponse response(
            String requestType,
            String entityType,
            String entityKey,
            String action,
            Map<String, Object> payload
    ) {
        return new AdminChangeRequestResponse(
                "acr-1",
                requestType,
                entityType,
                entityKey,
                action,
                "PENDING_REVIEW",
                "CHANGE_SUBMITTER",
                "SHRESTA admin",
                null,
                null,
                null,
                payload,
                Instant.parse("2026-07-05T00:00:00Z"),
                Instant.parse("2026-07-05T00:00:00Z"),
                null
        );
    }
}
