package com.shrestaexclusive.platform.admin.changes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shrestaexclusive.platform.asset.AssetService;
import com.shrestaexclusive.platform.category.admin.AdminCategoryService;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeItemUpdateCommand;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminChangeRequestApplierTest {

    private final AssetService assetService = mock(AssetService.class);
    private final AdminCategoryService categoryService = mock(AdminCategoryService.class);
    private final StorefrontHomeService storefrontHomeService = mock(StorefrontHomeService.class);
    private final AdminChangeRequestApplier applier = new AdminChangeRequestApplier(
            new ObjectMapper().registerModule(new JavaTimeModule()),
            assetService,
            categoryService,
            storefrontHomeService
    );

    @Test
    void appliesPermanentAssetDeleteRequests() {
        applier.apply(response(
                "asset-removal",
                "media_asset",
                "saree-silk-0001",
                "DELETE",
                Map.of("assetKey", "saree-silk-0001")
        ));

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
        ));

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
                                "pricePaise", 123000
                        ),
                        "media", Map.of(
                                "assetUrl", "categories/silk-saree-maroon-gold.png",
                                "altText", "Classic silk saree",
                                "widthPx", 800,
                                "heightPx", 800,
                                "deliveryMode", "s3-compatible-local"
                        )
                )
        ));

        ArgumentCaptor<StorefrontHomeItemUpdateCommand> captor = ArgumentCaptor.forClass(StorefrontHomeItemUpdateCommand.class);
        verify(storefrontHomeService).updateItem(captor.capture());
        StorefrontHomeItemUpdateCommand command = captor.getValue();
        assertThat(command.itemKey()).isEqualTo("product-shresta-ad-0001");
        assertThat(command.familyKey()).isEqualTo("silk_saree");
        assertThat(command.mediaUrl()).isEqualTo("categories/silk-saree-maroon-gold.png");
        assertThat(command.metadata()).containsEntry("sku", "SHRESTA-SILK-0001");
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
