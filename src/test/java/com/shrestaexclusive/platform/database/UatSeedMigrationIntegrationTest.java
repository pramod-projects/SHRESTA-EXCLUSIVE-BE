package com.shrestaexclusive.platform.database;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
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
import com.shrestaexclusive.platform.db.seed.DatabaseSeeder;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository;

@Testcontainers
@ActiveProfiles("uat")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "shresta.kv.enabled=false",
                "shresta.media.asset-base-url=http://localhost:9010/shresta-media"
        }
)
class UatSeedMigrationIntegrationTest {

    @Container
    @ServiceConnection
        @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shresta")
            .withUsername("shresta_app")
            .withPassword("change-me");

    @Autowired
    private JdbcTemplate jdbcTemplate;

        @Autowired
        private TestRestTemplate restTemplate;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private DatabaseSeeder databaseSeeder;

        @Autowired
        private StorefrontHomeRepository storefrontHomeRepository;

    @Test
    void uatProfileBootstrapsWebsiteShellWithoutMediaCatalogOrTestAccounts() {
        Integer logoCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM media_assets
                WHERE usage_type = 'brand'
                AND storage_key LIKE 'logos/%'
                """, Integer.class);
        Integer mediaAssetCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM media_assets
                """, Integer.class);
        Integer productAssetCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM media_assets
                WHERE product_sku IS NOT NULL
                """, Integer.class);
        Integer productCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM storefront_home_items item
                JOIN storefront_home_sections section ON section.id = item.section_id
                WHERE section.section_key = 'bestsellers'
                """, Integer.class);
        Integer testAccountCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM customer_accounts
                WHERE primary_email = 'testuser@gmail.com'
                """, Integer.class);
        Boolean brandLogoMissing = jdbcTemplate.queryForObject("""
                SELECT item.media_asset_id IS NULL
                FROM storefront_home_items item
                JOIN storefront_home_sections section ON section.id = item.section_id
                WHERE section.section_key = 'brand'
                """, Boolean.class);

        assertThat(logoCount).isZero();
        assertThat(mediaAssetCount).isZero();
        assertThat(productAssetCount).isZero();
        assertThat(productCount).isZero();
        assertThat(testAccountCount).isZero();
        assertThat(brandLogoMissing).isTrue();
    }

        @Test
        void uatStorefrontReturnsWebsiteBrandingWithNoProducts() throws Exception {
                ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/storefront/home", String.class);

                assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
                JsonNode data = objectMapper.readTree(response.getBody()).path("data");
                assertThat(data.path("brand").path("logo").isNull()).isTrue();
                assertThat(data.path("brand").path("demoVideoUrl").isNull()).isTrue();
                        assertThat(data.path("heroSlides")).hasSize(3);
                        assertThat(data.path("featuredCollections")).isNotEmpty();
                        assertThat(data.path("materialShowcase").path("stories")).isNotEmpty();
                assertThat(data.path("bestsellers").isArray()).isTrue();
                assertThat(data.path("bestsellers").isEmpty()).isTrue();
        }

                @Test
                void uatAndProdStartupPathPreservesAdminDisplayMediaAndCatalogBoundary() throws Exception {
                        String brandItemKey = jdbcTemplate.queryForObject("""
                                        SELECT item.item_key
                                        FROM storefront_home_items item
                                        JOIN storefront_home_sections section ON section.id = item.section_id
                                        WHERE section.section_key = 'brand'
                                        """, String.class);
                        UUID originalHeroMediaId = jdbcTemplate.queryForObject(
                                        "SELECT media_asset_id FROM storefront_home_items WHERE item_key = 'hero-kalankari'",
                                        UUID.class);
                        UUID originalBrandVideoId = jdbcTemplate.queryForObject(
                                        "SELECT video_media_asset_id FROM storefront_home_items WHERE item_key = ?",
                                        UUID.class, brandItemKey);
                        UUID videoId = UUID.randomUUID();
                        String videoAssetKey = "media-" + videoId.toString().replace("-", "");
                        UUID imageId = UUID.randomUUID();
                        String imageAssetKey = "media-" + imageId.toString().replace("-", "");
                        Integer productAssetsBefore = productAssetCount();
                        Integer bestsellersBefore = bestsellerCount();
                        try {
                                jdbcTemplate.update("""
                                                INSERT INTO media_assets (
                                                    id, asset_key, asset_url, alt_text, width_px, height_px,
                                                    usage_type, storage_key, content_type, byte_size, status, media_type
                                                ) VALUES (?, ?, ?, 'Restart test video', 1, 1, 'display', ?,
                                                          'video/mp4', 100, 'READY', 'DISPLAY_VIDEO')
                                                """, videoId, videoAssetKey, "display/videos/" + videoId + ".mp4",
                                                "display/videos/" + videoId + ".mp4");
                                jdbcTemplate.update("""
                                                INSERT INTO media_assets (
                                                    id, asset_key, asset_url, alt_text, width_px, height_px,
                                                    usage_type, storage_key, content_type, byte_size, status, media_type
                                                ) VALUES (?, ?, ?, 'Restart test image', 1, 1, 'display', ?,
                                                          'image/png', 100, 'READY', 'DISPLAY_IMAGE')
                                                """, imageId, imageAssetKey, "display/images/" + imageId + ".png",
                                                "display/images/" + imageId + ".png");
                                storefrontHomeRepository.updateDisplayMedia("hero-kalankari", imageAssetKey, null);
                                storefrontHomeRepository.updateDisplayMedia(brandItemKey, null, videoAssetKey);

                                databaseSeeder.run(new DefaultApplicationArguments(new String[0]));

                                assertThat(linkedAssetKey("hero-kalankari", "media_asset_id"))
                                                .isEqualTo(imageAssetKey);
                                assertThat(linkedAssetKey(brandItemKey, "video_media_asset_id"))
                                                .isEqualTo(videoAssetKey);
                                assertThat(productAssetCount()).isEqualTo(productAssetsBefore).isZero();
                                assertThat(bestsellerCount()).isEqualTo(bestsellersBefore).isZero();
                        } finally {
                                jdbcTemplate.update(
                                                "UPDATE storefront_home_items SET media_asset_id = ? WHERE item_key = 'hero-kalankari'",
                                                originalHeroMediaId);
                                jdbcTemplate.update(
                                                "UPDATE storefront_home_items SET video_media_asset_id = ? WHERE item_key = ?",
                                                originalBrandVideoId, brandItemKey);
                                jdbcTemplate.update("DELETE FROM media_assets WHERE id IN (?, ?)", videoId, imageId);
                        }
                }

                @Test
                @Transactional
                void uatRestartPreservesApprovedWebsiteContentAndMetadata() throws Exception {
                        String sectionKey = "hero";
                        String itemKey = "hero-kalankari";
                        jdbcTemplate.update("""
                                        UPDATE storefront_home_sections
                                        SET title = 'Approved hero title', metadata = '{"approved":true}'::jsonb
                                        WHERE section_key = ?
                                        """, sectionKey);
                        jdbcTemplate.update("""
                                        UPDATE storefront_home_items
                                        SET title = 'Approved item title', metadata = '{"approved":true}'::jsonb
                                        WHERE item_key = ?
                                        """, itemKey);

                        databaseSeeder.run(new DefaultApplicationArguments(new String[0]));

                        assertThat(jdbcTemplate.queryForObject(
                                        "SELECT title FROM storefront_home_sections WHERE section_key = ?",
                                        String.class, sectionKey)).isEqualTo("Approved hero title");
                        assertThat(jdbcTemplate.queryForObject(
                                        "SELECT metadata ->> 'approved' FROM storefront_home_sections WHERE section_key = ?",
                                        String.class, sectionKey)).isEqualTo("true");
                        assertThat(jdbcTemplate.queryForObject(
                                        "SELECT title FROM storefront_home_items WHERE item_key = ?",
                                        String.class, itemKey)).isEqualTo("Approved item title");
                        assertThat(jdbcTemplate.queryForObject(
                                        "SELECT metadata ->> 'approved' FROM storefront_home_items WHERE item_key = ?",
                                        String.class, itemKey)).isEqualTo("true");
                }

                @Test
                void uatRestartDoesNotRelinkArchivedOptionalReferenceMedia() throws Exception {
                        // Admin-managed asset carrying the local/dev seed's asset_key, archived in UAT.
                        UUID archivedBrandMarkId = UUID.randomUUID();
                        String materialItemKey = "material-brand-mark";
                        try {
                                jdbcTemplate.update("""
                                                INSERT INTO media_assets (
                                                    id, asset_key, asset_url, alt_text, width_px, height_px,
                                                    usage_type, storage_key, content_type, byte_size, status, media_type,
                                                    is_active
                                                ) VALUES (?, 'shresta-brand-mark-light', 'logos/SHRESTA_BRAND_MARK_LIGHT.png',
                                                          'Archived brand mark', 1, 1, 'brand', 'logos/SHRESTA_BRAND_MARK_LIGHT.png',
                                                          'image/png', 100, 'ARCHIVED', 'DISPLAY_IMAGE', FALSE)
                                                """, archivedBrandMarkId);
                                jdbcTemplate.update(
                                                "UPDATE storefront_home_items SET media_asset_id = NULL WHERE item_key = ?",
                                                materialItemKey);

                                databaseSeeder.run(new DefaultApplicationArguments(new String[0]));

                                assertThat(jdbcTemplate.queryForObject(
                                                "SELECT media_asset_id IS NULL FROM storefront_home_items WHERE item_key = ?",
                                                Boolean.class, materialItemKey)).isTrue();
                                assertThat(jdbcTemplate.queryForObject(
                                                "SELECT status FROM media_assets WHERE id = ?",
                                                String.class, archivedBrandMarkId)).isEqualTo("ARCHIVED");
                        } finally {
                                jdbcTemplate.update(
                                                "UPDATE storefront_home_items SET media_asset_id = NULL WHERE item_key = ?",
                                                materialItemKey);
                                jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", archivedBrandMarkId);
                        }
                }

                private String linkedAssetKey(String itemKey, String mediaColumn) {
                        return jdbcTemplate.queryForObject("""
                                        SELECT media.asset_key
                                        FROM storefront_home_items item
                                        JOIN media_assets media ON media.id = item.%s
                                        WHERE item.item_key = ?
                                        """.formatted(mediaColumn), String.class, itemKey);
                }

                private Integer productAssetCount() {
                        return jdbcTemplate.queryForObject(
                                        "SELECT count(*) FROM media_assets WHERE product_sku IS NOT NULL", Integer.class);
                }

                private Integer bestsellerCount() {
                        return jdbcTemplate.queryForObject("""
                                        SELECT count(*)
                                        FROM storefront_home_items item
                                        JOIN storefront_home_sections section ON section.id = item.section_id
                                        WHERE section.section_key = 'bestsellers'
                                        """, Integer.class);
                }
}
