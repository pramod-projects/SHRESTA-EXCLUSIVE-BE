package com.shrestaexclusive.platform.asset;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "shresta.admin.api-key=local-shresta-admin-key",
                "shresta.kv.enabled=false",
                "shresta.media.asset-base-url=http://localhost:9010/shresta-local-assets"
        }
)
class AdminAssetIntegrationTest {

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
        private AssetRepository assetRepository;

    @Test
    void assetManagerExcludesBrandSystemAssets() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "local-shresta-admin-key");
        headers.set(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, "CHANGE_SUBMITTER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/assets?size=100",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode assets = objectMapper.readTree(response.getBody()).path("data").path("assets");
        JsonNode firstAsset = assets.get(0);

        assertThat(StreamSupport.stream(assets.spliterator(), false)
                .map(node -> node.path("assetKey").asText())
                .toList())
                .isNotEmpty()
                .doesNotContain("shresta-logo-light");
        assertThat(firstAsset.path("assetUrl").asText())
                .startsWith("http://localhost:9010/shresta-local-assets/")
                .doesNotContain("/shresta-media", "/shresta-assets");
    }

        @Test
        void assetManagerExcludesStagedUploadUntilApprovalLinksIt() {
                UUID originalHeroAssetId = jdbcTemplate.queryForObject(
                                "SELECT media_asset_id FROM storefront_home_items WHERE item_key = 'hero-kalankari'",
                                UUID.class);
                AssetSearchResponse beforeUpload = assetRepository.search(null, null, null, null, null, 0, 100);
                UUID id = UUID.randomUUID();
                String assetKey = "media-" + id.toString().replace("-", "");
                try {
                        jdbcTemplate.update("""
                                        INSERT INTO media_assets (
                                                id, asset_key, asset_url, alt_text, width_px, height_px,
                                                usage_type, storage_key, content_type, byte_size, status, media_type
                                        ) VALUES (?, ?, ?, 'Staged display image', 1200, 1600,
                                                  'asset-manager', ?, 'image/webp', 100, 'READY', 'DISPLAY_IMAGE')
                                        """, id, assetKey, "display/images/" + id + ".webp",
                                        "display/images/" + id + ".webp");

                        AssetSearchResponse stagedUpload = assetRepository.search(null, null, null, null, null, 0, 100);
                        assertThat(stagedUpload.assets()).extracting(AssetResponse::assetKey).doesNotContain(assetKey);
                        assertThat(stagedUpload.total()).isEqualTo(beforeUpload.total());
                        assertThat(stagedUpload.systemTotal()).isEqualTo(beforeUpload.systemTotal() + 1);
                        assertThat(stagedUpload.systemImageTotal() + stagedUpload.systemVideoTotal()
                                        + stagedUpload.systemOtherTotal()).isEqualTo(stagedUpload.systemTotal());
                        assertThat(stagedUpload.systemReferencedTotal() + stagedUpload.systemUnreferencedTotal())
                                        .isEqualTo(stagedUpload.systemTotal());

                        jdbcTemplate.update(
                                        "UPDATE storefront_home_items SET media_asset_id = ? WHERE item_key = 'hero-kalankari'",
                                        id);

                        AssetSearchResponse approvedUpload = assetRepository.search(null, null, null, null, null, 0, 100);
                        assertThat(approvedUpload.assets()).extracting(AssetResponse::assetKey).contains(assetKey);
                        assertThat(approvedUpload.total()).isEqualTo(beforeUpload.total() + 1);
                        assertThat(approvedUpload.systemTotal()).isEqualTo(beforeUpload.systemTotal() + 1);
                } finally {
                        jdbcTemplate.update(
                                        "UPDATE storefront_home_items SET media_asset_id = ? WHERE item_key = 'hero-kalankari'",
                                        originalHeroAssetId);
                        jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", id);
                }
        }

        @Test
        void abandonedDisplayCleanupExcludesPendingAndLinkedAssets() {
                UUID originalHeroAssetId = jdbcTemplate.queryForObject("""
                                SELECT media_asset_id FROM storefront_home_items WHERE item_key = 'hero-kalankari'
                                """, UUID.class);
                String orphan = insertOldDisplayAsset("DISPLAY_IMAGE", "image/webp");
                String pending = insertOldDisplayAsset("DISPLAY_IMAGE", "image/webp");
                String linked = insertOldDisplayAsset("DISPLAY_IMAGE", "image/webp");
                String requestKey = "acr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                try {
                        jdbcTemplate.update("UPDATE storefront_home_items SET media_asset_id = (SELECT id FROM media_assets WHERE asset_key = ?) WHERE item_key = 'hero-kalankari'", linked);
                        jdbcTemplate.update("""
                                        INSERT INTO admin_change_requests (
                                                request_key, request_type, entity_type, entity_key, action,
                                                submitted_by_role, submitted_by, payload
                                        ) VALUES (?, 'storefront-display-media', 'storefront_home_item',
                                                          'hero-kalankari', 'UPDATE', 'CHANGE_SUBMITTER', 'test@example.com',
                                                          jsonb_build_object('imageAssetKey', ?))
                                        """, requestKey, pending);

                        List<String> candidates = assetRepository.findAbandonedDisplayAssetKeys(Instant.now().minusSeconds(86_400), 100);

                        assertThat(candidates).contains(orphan).doesNotContain(pending, linked);
                } finally {
                        jdbcTemplate.update("DELETE FROM admin_change_requests WHERE request_key = ?", requestKey);
                        jdbcTemplate.update("UPDATE storefront_home_items SET media_asset_id = ? WHERE item_key = 'hero-kalankari'", originalHeroAssetId);
                        jdbcTemplate.update("DELETE FROM media_assets WHERE asset_key IN (?, ?, ?)", orphan, pending, linked);
                }
        }

        @Test
        void storefrontUnreferencedSearchListsOnlyUnlinkedAssets() throws Exception {
                String unlinked = insertAdminManagedAsset("DISPLAY_IMAGE", "image/webp");
                String linked = insertAdminManagedAsset("DISPLAY_IMAGE", "image/webp");
                UUID originalHeroAssetId = jdbcTemplate.queryForObject(
                                "SELECT media_asset_id FROM storefront_home_items WHERE item_key = 'hero-kalankari'",
                                UUID.class);
                try {
                        jdbcTemplate.update("""
                                        UPDATE storefront_home_items
                                        SET media_asset_id = (SELECT id FROM media_assets WHERE asset_key = ?)
                                        WHERE item_key = 'hero-kalankari'
                                        """, linked);

                        StorefrontUnreferencedAssetsResponse unreferenced =
                                        assetRepository.searchStorefrontUnreferenced(null, null, 0, 100);

                        assertThat(unreferenced.items()).extracting(AssetResponse::assetKey)
                                        .contains(unlinked)
                                        .doesNotContain(linked);
                        assertThat(unreferenced.size()).isEqualTo(100);
                        assertThat(unreferenced.total()).isGreaterThanOrEqualTo(unreferenced.items().size());

                        StorefrontUnreferencedAssetsResponse filtered =
                                        assetRepository.searchStorefrontUnreferenced(unlinked, null, 0, 100);
                        assertThat(filtered.items()).extracting(AssetResponse::assetKey)
                                        .containsExactly(unlinked);
                        assertThat(filtered.total()).isEqualTo(1);
                } finally {
                        jdbcTemplate.update(
                                        "UPDATE storefront_home_items SET media_asset_id = ? WHERE item_key = 'hero-kalankari'",
                                        originalHeroAssetId);
                        jdbcTemplate.update("DELETE FROM media_assets WHERE asset_key IN (?, ?)", unlinked, linked);
                }
        }

        @Test
        void productMediaLinkEligibilityRequiresReadyUnreferencedAsset() {
                String unlinked = insertAdminManagedAsset("DISPLAY_IMAGE", "image/webp");
                UUID heroAssetId = jdbcTemplate.queryForObject(
                                "SELECT media_asset_id FROM storefront_home_items WHERE item_key = 'hero-kalankari'",
                                UUID.class);
                String referenced = jdbcTemplate.queryForObject(
                                "SELECT asset_key FROM media_assets WHERE id = ?",
                                String.class, heroAssetId);
                try {
                        assertThat(assetRepository.storefrontProductMediaLinkEligible(
                                        "product-shresta-kalankari-0001", unlinked, "PRODUCT_IMAGE")).isTrue();
                        assertThat(assetRepository.storefrontProductMediaLinkEligible(
                                        "product-shresta-kalankari-0001", referenced, "PRODUCT_IMAGE")).isFalse();
                        assertThat(assetRepository.storefrontProductMediaLinkEligible(
                                        "product-unknown", unlinked, "PRODUCT_IMAGE")).isFalse();

                        assertThat(assetRepository.reassignStorefrontProductMedia(
                                        "product-shresta-kalankari-0001", unlinked, "PRODUCT_IMAGE")).isTrue();
                        assertThat(jdbcTemplate.queryForObject(
                                        "SELECT product_sku FROM media_assets WHERE asset_key = ?",
                                        String.class, unlinked)).isEqualTo("product-shresta-kalankari-0001");
                        assertThat(jdbcTemplate.queryForObject(
                                        "SELECT media_type FROM media_assets WHERE asset_key = ?",
                                        String.class, unlinked)).isEqualTo("PRODUCT_IMAGE");

                        assertThat(assetRepository.reassignStorefrontProductMedia(
                                        "product-shresta-kalankari-0001", referenced, "PRODUCT_IMAGE")).isFalse();
                } finally {
                        jdbcTemplate.update("DELETE FROM media_assets WHERE asset_key = ?", unlinked);
                }
        }

        @Test
        void productMediaLinkEligibilityRejectsOwnedAssetsOfADifferentMediaType() {
                String itemKey = "product-shresta-kalankari-0001";
                String ownedVideo = insertAdminManagedAsset("PRODUCT_VIDEO", "video/mp4", itemKey);
                String ownedImage = insertAdminManagedAsset("PRODUCT_IMAGE", "image/webp", itemKey);
                UUID originalVideoAssetId = jdbcTemplate.queryForObject(
                                "SELECT video_media_asset_id FROM storefront_home_items WHERE item_key = ?",
                                UUID.class, itemKey);
                try {
                        jdbcTemplate.update("""
                                        UPDATE storefront_home_items
                                        SET video_media_asset_id = (SELECT id FROM media_assets WHERE asset_key = ?)
                                        WHERE item_key = ?
                                        """, ownedVideo, itemKey);

                        assertThat(assetRepository.storefrontProductMediaLinkEligible(
                                        itemKey, ownedVideo, "PRODUCT_IMAGE")).isFalse();
                        assertThat(assetRepository.reassignStorefrontProductMedia(
                                        itemKey, ownedVideo, "PRODUCT_IMAGE")).isFalse();
                        assertThat(jdbcTemplate.queryForObject(
                                        "SELECT media_type FROM media_assets WHERE asset_key = ?",
                                        String.class, ownedVideo)).isEqualTo("PRODUCT_VIDEO");

                        assertThat(assetRepository.storefrontProductMediaLinkEligible(
                                        itemKey, ownedImage, "PRODUCT_IMAGE")).isTrue();
                        assertThat(assetRepository.reassignStorefrontProductMedia(
                                        itemKey, ownedImage, "PRODUCT_IMAGE")).isTrue();

                        assertThat(assetRepository.storefrontProductMediaLinkEligible(
                                        itemKey, ownedVideo, "PRODUCT_VIDEO")).isTrue();
                } finally {
                        jdbcTemplate.update(
                                        "UPDATE storefront_home_items SET video_media_asset_id = ? WHERE item_key = ?",
                                        originalVideoAssetId, itemKey);
                        jdbcTemplate.update("DELETE FROM media_assets WHERE asset_key IN (?, ?)", ownedVideo, ownedImage);
                }
        }

        @Test
        void permanentDeleteRemovesInactiveGalleryReferences() {
                String asset = insertAdminManagedAsset("PRODUCT_IMAGE", "image/webp");
                try {
                        Integer ghostSlot = jdbcTemplate.queryForObject("""
                                        SELECT slot FROM generate_series(1, 4) AS slots(slot)
                                        WHERE NOT EXISTS (
                                                SELECT 1
                                                FROM storefront_home_item_gallery gallery
                                                JOIN storefront_home_items item ON item.id = gallery.item_id
                                                WHERE item.item_key = 'product-shresta-kalankari-0001'
                                                  AND gallery.sort_order = slots.slot
                                        )
                                        ORDER BY slot
                                        LIMIT 1
                                        """, Integer.class);
                        assertThat(ghostSlot).isNotNull();
                        jdbcTemplate.update("""
                                        INSERT INTO storefront_home_item_gallery (item_id, media_asset_id, sort_order, is_active)
                                        SELECT item.id, (SELECT id FROM media_assets WHERE asset_key = ?), ?, FALSE
                                        FROM storefront_home_items item
                                        WHERE item.item_key = 'product-shresta-kalankari-0001'
                                        """, asset, ghostSlot);

                        assetRepository.deletePermanently(asset);

                        assertThat(jdbcTemplate.queryForObject(
                                        "SELECT count(*) FROM storefront_home_item_gallery WHERE media_asset_id = (SELECT id FROM media_assets WHERE asset_key = ?)",
                                        Long.class, asset)).isZero();
                        assertThat(jdbcTemplate.queryForObject(
                                        "SELECT count(*) FROM media_assets WHERE asset_key = ?",
                                        Long.class, asset)).isZero();
                } finally {
                        jdbcTemplate.update("""
                                        DELETE FROM storefront_home_item_gallery
                                        WHERE media_asset_id = (SELECT id FROM media_assets WHERE asset_key = ?)
                                        """, asset);
                        jdbcTemplate.update("DELETE FROM media_assets WHERE asset_key = ?", asset);
                }
        }

        @Test
        void abandonedDisplayCleanupExcludesAssetsWithPendingMediaLinkRequest() {
                String orphan = insertOldDisplayAsset("DISPLAY_IMAGE", "image/webp");
                String linkPending = insertOldDisplayAsset("DISPLAY_IMAGE", "image/webp");
                String requestKey = "acr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                try {
                        jdbcTemplate.update("""
                                        INSERT INTO admin_change_requests (
                                                request_key, request_type, entity_type, entity_key, action,
                                                submitted_by_role, submitted_by, payload
                                        ) VALUES (?, 'storefront-product-media-link', 'storefront_home_items',
                                                          'product-shresta-kalankari-0001', 'UPDATE', 'CHANGE_SUBMITTER', 'test@example.com',
                                                          jsonb_build_object('assetKey', ?, 'slot', 'PRIMARY'))
                                        """, requestKey, linkPending);

                        List<String> candidates = assetRepository.findAbandonedDisplayAssetKeys(Instant.now().minusSeconds(86_400), 100);

                        assertThat(candidates).contains(orphan).doesNotContain(linkPending);

                        jdbcTemplate.update("DELETE FROM admin_change_requests WHERE request_key = ?", requestKey);

                        assertThat(assetRepository.findAbandonedDisplayAssetKeys(Instant.now().minusSeconds(86_400), 100))
                                        .contains(linkPending);
                } finally {
                        jdbcTemplate.update("DELETE FROM admin_change_requests WHERE request_key = ?", requestKey);
                        jdbcTemplate.update("DELETE FROM media_assets WHERE asset_key IN (?, ?)", orphan, linkPending);
                }
        }

        @Test
        void productMediaAssetKeysExcludeAssetsWithPendingMediaLinkRequest() {
                String productId = "product-expiry-test-" + UUID.randomUUID().toString().substring(0, 8);
                String owned = insertAdminManagedAsset("PRODUCT_IMAGE", "image/webp", productId);
                String requestKey = "acr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                try {
                        jdbcTemplate.update("""
                                        INSERT INTO admin_change_requests (
                                                request_key, request_type, entity_type, entity_key, action,
                                                submitted_by_role, submitted_by, payload
                                        ) VALUES (?, 'storefront-product-media-link', 'storefront_home_items',
                                                          'product-shresta-kalankari-0001', 'UPDATE', 'CHANGE_SUBMITTER', 'test@example.com',
                                                          jsonb_build_object('assetKey', ?, 'slot', 'GALLERY_1'))
                                        """, requestKey, owned);

                        assertThat(assetRepository.findProductMediaAssetKeys(productId)).doesNotContain(owned);

                        jdbcTemplate.update("DELETE FROM admin_change_requests WHERE request_key = ?", requestKey);

                        assertThat(assetRepository.findProductMediaAssetKeys(productId)).contains(owned);
                } finally {
                        jdbcTemplate.update("DELETE FROM admin_change_requests WHERE request_key = ?", requestKey);
                        jdbcTemplate.update("DELETE FROM media_assets WHERE asset_key = ?", owned);
                }
        }

        private String insertAdminManagedAsset(String mediaType, String contentType, String productSku) {
                UUID id = UUID.randomUUID();
                String assetKey = "media-" + id.toString().replace("-", "");
                jdbcTemplate.update("""
                                INSERT INTO media_assets (
                                        id, asset_key, asset_url, alt_text, width_px, height_px, delivery_mode,
                                        usage_type, storage_provider, storage_key, product_sku, content_type, byte_size,
                                        status, media_type
                                ) VALUES (?, ?, ?, 'Unreferenced test', 1200, 1600, 'cloudflare-r2',
                                                  'asset-manager', 'cloudflare-r2', ?, ?, ?, 100, 'READY', ?)
                                """, id, assetKey, "display/images/" + id + ".webp", "display/images/" + id + ".webp", productSku, contentType, mediaType);
                return assetKey;
        }

        private String insertAdminManagedAsset(String mediaType, String contentType) {
                UUID id = UUID.randomUUID();
                String assetKey = "media-" + id.toString().replace("-", "");
                jdbcTemplate.update("""
                                INSERT INTO media_assets (
                                        id, asset_key, asset_url, alt_text, width_px, height_px, delivery_mode,
                                        usage_type, storage_provider, storage_key, content_type, byte_size,
                                        status, media_type
                                ) VALUES (?, ?, ?, 'Unreferenced test', 1200, 1600, 'cloudflare-r2',
                                                  'asset-manager', 'cloudflare-r2', ?, ?, 100, 'READY', ?)
                                """, id, assetKey, "display/images/" + id + ".webp", "display/images/" + id + ".webp", contentType, mediaType);
                return assetKey;
        }

        private String insertOldDisplayAsset(String mediaType, String contentType) {
                UUID id = UUID.randomUUID();
                String assetKey = "media-" + id.toString().replace("-", "");
                jdbcTemplate.update("""
                                INSERT INTO media_assets (
                                        id, asset_key, asset_url, alt_text, width_px, height_px, delivery_mode,
                                        usage_type, storage_provider, storage_key, content_type, byte_size,
                                        status, media_type, created_at, updated_at
                                ) VALUES (?, ?, ?, 'Cleanup test', 1200, 1600, 'cloudflare-r2',
                                                  'display', 'cloudflare-r2', ?, ?, 100, 'READY', ?, now() - interval '2 days', now() - interval '2 days')
                                """, id, assetKey, "display/images/" + id + ".webp", "display/images/" + id + ".webp", contentType, mediaType);
                return assetKey;
        }

}
