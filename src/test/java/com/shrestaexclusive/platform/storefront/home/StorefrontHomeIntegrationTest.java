package com.shrestaexclusive.platform.storefront.home;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.admin.changes.AdminChangeRequestCreateRequest;
import com.shrestaexclusive.platform.admin.changes.AdminChangeRequestDecisionRequest;
import com.shrestaexclusive.platform.admin.changes.AdminChangeRequestResponse;
import com.shrestaexclusive.platform.admin.changes.AdminChangeRequestService;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "shresta.kv.enabled=false",
                "shresta.media.asset-base-url=http://localhost:9010/shresta-local-assets"
        }
)
class StorefrontHomeIntegrationTest {

    @Container
    @ServiceConnection
        @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shresta")
            .withUsername("shresta_app")
            .withPassword("change-me");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Autowired
        private StorefrontHomeRepository repository;

        @Autowired
        private AdminChangeRequestService adminChangeRequestService;

    @Test
    void returnsDatabaseSeededMultiCategoryStorefrontHome() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/storefront/home", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode root = objectMapper.readTree(response.getBody());

        assertThat(root.path("success").asBoolean()).isTrue();
        JsonNode data = root.path("data");
        assertThat(data.path("brand").path("logo").path("url").asText())
                .startsWith("http://localhost:9010/")
                .endsWith("/logos/SHRESTA_LOGO_LIGHT.png");
        assertThat(data.path("featuredCollections"))
                .extracting(node -> node.path("familyKey").asText())
                .containsOnly("silk_saree");
        assertThat(data.path("bestsellers")).isNotEmpty();
        assertThat(data.path("bestsellers"))
                .extracting(node -> node.path("sku").asText())
                .allMatch(sku -> sku != null && !((String) sku).isBlank());
        assertThat(data.toString()).doesNotContain("data:image");
    }

                @Test
                void displayItemCanReferenceExistingTaggedProductImage() throws Exception {
                                String productKey = jdbcTemplate.queryForObject("""
                                                                SELECT item.item_key
                                                                FROM storefront_home_items item
                                                                JOIN storefront_home_sections section ON section.id = item.section_id
                                                                WHERE section.section_key = 'bestsellers' AND item.is_active = TRUE
                                                                LIMIT 1
                                                                """, String.class);
                                String assetKey = insertProductImage(productKey, "READY");
                                jdbcTemplate.update("UPDATE media_assets SET tags = '[\"COLOR_MAROON\"]'::jsonb WHERE asset_key = ?", assetKey);

                                repository.updateDisplayMedia("hero-kalankari", assetKey, null);

                                ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/storefront/home", String.class);
                                JsonNode hero = objectMapper.readTree(response.getBody()).path("data").path("heroSlides").get(0);
                                assertThat(hero.path("image").path("assetKey").asText()).isEqualTo(assetKey);
                                assertThat(hero.path("image").path("tags"))
                                                                .extracting(JsonNode::asText)
                                                                .containsExactly("COLOR_MAROON");
                }

                                        @Test
                                        void displayAssignmentCannotBypassProductMediaOwnership() {
                                                List<String> productKeys = jdbcTemplate.queryForList("""
                                                                SELECT item.item_key
                                                                FROM storefront_home_items item
                                                                JOIN storefront_home_sections section ON section.id = item.section_id
                                                                WHERE section.section_key = 'bestsellers' AND item.is_active = TRUE
                                                                ORDER BY item.item_key
                                                                LIMIT 2
                                                                """, String.class);
                                                String assetKey = insertProductImage(productKeys.get(0), "READY");

                                                assertThatThrownBy(() -> repository.updateDisplayMedia(productKeys.get(1), assetKey, null))
                                                                .isInstanceOf(StorefrontMediaAssignmentException.class)
                                                                .hasMessage("A READY image asset is required");
                                        }

        @Test
        void approvingDisplayVideoLinksItToTheBrandItem() {
                String actor = "display-admin@example.com";
                String brandItemKey = jdbcTemplate.queryForObject("""
                                SELECT item.item_key
                                FROM storefront_home_items item
                                JOIN storefront_home_sections section ON section.id = item.section_id
                                WHERE section.section_key = 'brand' AND item.is_active = TRUE
                                """, String.class);
                UUID originalVideoId = jdbcTemplate.queryForObject(
                                "SELECT video_media_asset_id FROM storefront_home_items WHERE item_key = ?",
                                UUID.class, brandItemKey);
                DisplayVideo displayVideo = insertDisplayVideo(actor);
                AdminChangeRequestResponse changeRequest = null;
                try {
                        changeRequest = adminChangeRequestService.create(
                                        "CHANGE_SUBMITTER",
                                        actor,
                                        new AdminChangeRequestCreateRequest(
                                                        "storefront-display-media",
                                                        "storefront_home_item",
                                                        brandItemKey,
                                                        "UPDATE",
                                                        actor,
                                                        java.util.Map.of(
                                                                        "videoMediaId", displayVideo.id().toString(),
                                                                        "videoAssetKey", displayVideo.assetKey())));

                        AdminChangeRequestResponse approved = adminChangeRequestService.approve(
                                        changeRequest.requestKey(),
                                        "CHANGE_APPROVER",
                                        new AdminChangeRequestDecisionRequest("reviewer@example.com", "Approved demo video"));

                        assertThat(approved.status()).isEqualTo("APPROVED");
                        assertThat(jdbcTemplate.queryForObject(
                                        "SELECT video_media_asset_id FROM storefront_home_items WHERE item_key = ?",
                                        UUID.class, brandItemKey)).isEqualTo(displayVideo.id());
                } finally {
                        jdbcTemplate.update(
                                        "UPDATE storefront_home_items SET video_media_asset_id = ? WHERE item_key = ?",
                                        originalVideoId, brandItemKey);
                        if (changeRequest != null) {
                                jdbcTemplate.update("DELETE FROM admin_change_requests WHERE request_key = ?", changeRequest.requestKey());
                        }
                        jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", displayVideo.id());
                }
        }

        @Test
        void galleryAcceptsOnlyReadyImagesOwnedByTheProduct() {
                List<String> productKeys = jdbcTemplate.queryForList("""
                                SELECT item.item_key
                                FROM storefront_home_items item
                                JOIN storefront_home_sections section ON section.id = item.section_id
                                WHERE section.section_key = 'bestsellers' AND item.is_active = TRUE
                                ORDER BY item.item_key
                                LIMIT 2
                                """, String.class);
                assertThat(productKeys).hasSize(2);
                String ownerProduct = productKeys.get(0);
                String otherProduct = productKeys.get(1);
                String readyAssetKey = insertProductImage(ownerProduct, "READY");
                String pendingAssetKey = insertProductImage(ownerProduct, "PENDING_UPLOAD");

                assertThatThrownBy(() -> repository.updateGallerySlot(otherProduct, 1, readyAssetKey))
                        .isInstanceOf(StorefrontMediaAssignmentException.class)
                                .hasMessage("A READY product image owned by this product is required");
                assertThatThrownBy(() -> repository.updateGallerySlot(ownerProduct, 1, pendingAssetKey))
                        .isInstanceOf(StorefrontMediaAssignmentException.class)
                                .hasMessage("A READY product image owned by this product is required");

                repository.updateGallerySlot(ownerProduct, 1, readyAssetKey);
                String linkedAsset = jdbcTemplate.queryForObject("""
                                SELECT media.asset_key
                                FROM storefront_home_item_gallery gallery
                                JOIN storefront_home_items item ON item.id = gallery.item_id
                                JOIN media_assets media ON media.id = gallery.media_asset_id
                                WHERE item.item_key = ? AND gallery.sort_order = 1 AND gallery.is_active = TRUE
                                """, String.class, ownerProduct);
                assertThat(linkedAsset).isEqualTo(readyAssetKey);
        }

        @Test
        void productCreationRequiresReadyPrimaryImageOwnedByNewProduct() {
                String newProductKey = "product-" + UUID.randomUUID();
                String otherProductKey = jdbcTemplate.queryForObject("""
                                SELECT item.item_key
                                FROM storefront_home_items item
                                JOIN storefront_home_sections section ON section.id = item.section_id
                                WHERE section.section_key = 'bestsellers' AND item.is_active = TRUE
                                LIMIT 1
                                """, String.class);
                String otherProductImage = insertProductImage(otherProductKey, "READY");
                String pendingImage = insertProductImage(newProductKey, "PENDING_UPLOAD");

                assertThatThrownBy(() -> repository.createItem(createProduct(newProductKey, otherProductImage)))
                                .isInstanceOf(StorefrontMediaAssignmentException.class)
                                .hasMessage("A READY primary product image owned by this product is required");
                assertThatThrownBy(() -> repository.createItem(createProduct(newProductKey, pendingImage)))
                                .isInstanceOf(StorefrontMediaAssignmentException.class)
                                .hasMessage("A READY primary product image owned by this product is required");
                assertThat(jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM storefront_home_items WHERE item_key = ?",
                                Integer.class,
                                newProductKey)).isZero();
        }

                                @Test
                                @Transactional
                                void productIdentityMustBeUniqueAcrossActiveProducts() {
                                                                Map<String, Object> identity = jdbcTemplate.queryForMap("""
                                                                                                                                SELECT item.item_key, item.metadata ->> 'sku' AS sku, item.metadata ->> 'slug' AS slug
                                                                                                                                FROM storefront_home_items item
                                                                                                                                JOIN storefront_home_sections section ON section.id = item.section_id
                                                                                                                                WHERE section.section_key = 'bestsellers'
                                                                                                                                        AND item.is_active = TRUE
                                                                                                                                        AND item.metadata ->> 'sku' IS NOT NULL
                                                                                                                                        AND item.metadata ->> 'slug' IS NOT NULL
                                                                                                                                LIMIT 1
                                                                                                                                """);

                                                                assertThatThrownBy(() -> repository.assertUniqueProductIdentity(
                                                                                                                                "different-product-key",
                                                                                                                                identity.get("sku").toString(),
                                                                                                                                identity.get("slug").toString()
                                                                )).isInstanceOf(StorefrontProductIdentityConflictException.class)
                                                                        .hasMessage("Product SKU and slug must be unique");
                                }

            @Test
            void productVideoAcceptsOnlyReadyVideoOwnedByTheProduct() {
                List<String> productKeys = jdbcTemplate.queryForList("""
                        SELECT item.item_key
                        FROM storefront_home_items item
                        JOIN storefront_home_sections section ON section.id = item.section_id
                        WHERE section.section_key = 'bestsellers' AND item.is_active = TRUE
                        ORDER BY item.item_key
                        LIMIT 2
                        """, String.class);
                String ownerProduct = productKeys.get(0);
                String otherProduct = productKeys.get(1);
                String readyVideoKey = insertProductVideo(ownerProduct, "READY");
                String pendingVideoKey = insertProductVideo(ownerProduct, "PENDING_UPLOAD");

                assertThatThrownBy(() -> updateVideo(otherProduct, readyVideoKey))
                        .isInstanceOf(StorefrontMediaAssignmentException.class)
                        .hasMessage("A READY product video owned by this product is required");
                assertThatThrownBy(() -> updateVideo(ownerProduct, pendingVideoKey))
                        .isInstanceOf(StorefrontMediaAssignmentException.class)
                        .hasMessage("A READY product video owned by this product is required");

                updateVideo(ownerProduct, readyVideoKey);
                String linkedAsset = jdbcTemplate.queryForObject("""
                        SELECT media.asset_key
                        FROM storefront_home_items item
                        JOIN media_assets media ON media.id = item.video_media_asset_id
                        WHERE item.item_key = ?
                        """, String.class, ownerProduct);
                assertThat(linkedAsset).isEqualTo(readyVideoKey);
            }

            private void updateVideo(String productKey, String assetKey) {
                repository.updateItem(new StorefrontHomeItemUpdateCommand(
                        productKey, null, null, null, null, null, null, null, null,
                        null, null, null, assetKey
                ));
            }

                private StorefrontHomeItemCreateCommand createProduct(String productKey, String primaryAssetKey) {
                                return new StorefrontHomeItemCreateCommand(
                                                                "bestsellers", productKey, "silk_saree", "Integration product",
                                                                null, null, null, null, 0, false, java.util.Map.of("pricePaise", 10000),
                                                                primaryAssetKey, List.of(), null);
                }

        private String insertProductImage(String productKey, String status) {
                UUID id = UUID.randomUUID();
                String assetKey = "media-" + id.toString().replace("-", "");
                String objectKey = "products/" + productKey + "/images/" + id + ".webp";
                jdbcTemplate.update("""
                                INSERT INTO media_assets (
                                        id, asset_key, asset_url, alt_text, width_px, height_px, delivery_mode,
                                        usage_type, storage_provider, storage_key, product_sku, content_type,
                                        byte_size, status, media_type, upload_expires_at
                                ) VALUES (?, ?, ?, 'Integration image', 1800, 2400, 'cloudflare-r2',
                                        'asset-manager', 'cloudflare-r2', ?, ?, 'image/webp', 120000, ?,
                                        'PRODUCT_IMAGE', now() + interval '10 minutes')
                                """, id, assetKey, objectKey, objectKey, productKey, status);
                return assetKey;
        }

        @Test
        @Transactional
        void productMetadataUpdatePreservesUnmodeledKeys() {
                String itemKey = jdbcTemplate.queryForObject("""
                                SELECT item.item_key
                                FROM storefront_home_items item
                                JOIN storefront_home_sections section ON section.id = item.section_id
                                WHERE section.section_key = 'bestsellers' AND item.is_active = TRUE
                                LIMIT 1
                                """, String.class);
                jdbcTemplate.update(
                                "UPDATE storefront_home_items SET metadata = metadata || '{\"attributes\":{\"weave\":\"legacy\"}}'::jsonb WHERE item_key = ?",
                                itemKey);

                repository.updateItem(new StorefrontHomeItemUpdateCommand(
                                itemKey, null, null, null, null, null, null, null, null,
                                Map.of("pricePaise", 12345), null, null, null
                ));

                String metadata = jdbcTemplate.queryForObject(
                                "SELECT metadata::text FROM storefront_home_items WHERE item_key = ?",
                                String.class, itemKey);
                assertThat(metadata).contains("\"pricePaise\": 12345", "\"weave\": \"legacy\"");
        }

        private String insertProductVideo(String productKey, String status) {
                UUID id = UUID.randomUUID();
                String assetKey = "media-" + id.toString().replace("-", "");
                String objectKey = "products/" + productKey + "/videos/" + id + ".mp4";
                jdbcTemplate.update("""
                                INSERT INTO media_assets (
                                        id, asset_key, asset_url, alt_text, width_px, height_px, delivery_mode,
                                        usage_type, storage_provider, storage_key, product_sku, content_type,
                                        byte_size, status, media_type, upload_expires_at
                                ) VALUES (?, ?, ?, 'Integration video', 1, 1, 'cloudflare-r2',
                                        'asset-manager', 'cloudflare-r2', ?, ?, 'video/mp4', 2000000, ?,
                                        'PRODUCT_VIDEO', now() + interval '10 minutes')
                                """, id, assetKey, objectKey, objectKey, productKey, status);
                return assetKey;
        }

        private DisplayVideo insertDisplayVideo(String actor) {
                UUID id = UUID.randomUUID();
                String assetKey = "media-" + id.toString().replace("-", "");
                String objectKey = "display/videos/" + id + ".mp4";
                jdbcTemplate.update("""
                                INSERT INTO media_assets (
                                        id, asset_key, asset_url, alt_text, width_px, height_px, delivery_mode,
                                        usage_type, storage_provider, storage_key, content_type, byte_size,
                                        status, media_type, uploaded_by
                                ) VALUES (?, ?, ?, 'Integration display video', 1, 1, 'cloudflare-r2',
                                        'asset-manager', 'cloudflare-r2', ?, 'video/mp4', 2000000,
                                        'READY', 'DISPLAY_VIDEO', ?)
                                """, id, assetKey, objectKey, objectKey, actor);
                return new DisplayVideo(id, assetKey);
        }

        private record DisplayVideo(UUID id, String assetKey) {
        }
}
