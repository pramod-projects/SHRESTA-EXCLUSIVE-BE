package com.shrestaexclusive.platform.category.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.shrestaexclusive.platform.category.admin.AdminCategoryService;
import com.shrestaexclusive.platform.db.seed.DatabaseSeeder;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shresta.media.asset-base-url=http://localhost:9010/shresta-local-assets"
)
class CategoryConfigIntegrationTest {

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
    private DatabaseSeeder databaseSeeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminCategoryService adminCategoryService;

    @Test
    void returnsFlywaySeededCategoryConfiguration() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/categories", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("success").asBoolean()).isTrue();

        JsonNode data = root.path("data");
        assertThat(data.isArray()).isTrue();
        if (!data.isEmpty()) {
            assertThat(familyKeys(data)).contains("silk_saree");
            assertThat(data.get(0).path("productTypes")).isNotNull();
            assertThat(data.get(0).path("attributes")).isNotNull();
            assertThat(data.get(0).path("filters").isArray()).isTrue();
        }
    }

    @Test
    void repeatedStartupSeedingDoesNotDuplicateAnySeededTable() throws Exception {
        List<String> seededTables = List.of(
                "category_family_config",
                "category_product_type_config",
                "category_attribute_config",
                "category_filter_config",
                "category_tax_config",
                "category_styling_config",
                "media_assets",
                "storefront_home_sections",
                "storefront_home_items",
                "storefront_store_sections",
                "store_locations",
                "customer_accounts",
                "customer_auth_identities",
                "uat_seed_accounts"
        );
        Map<String, Long> firstCounts = tableCounts(seededTables);

        databaseSeeder.run(new DefaultApplicationArguments(new String[0]));

        assertThat(tableCounts(seededTables)).isEqualTo(firstCounts);
    }

        @Test
        @Transactional
        void merchandisingUpdatePreservesUnrelatedFamilyMetadata() {
        jdbcTemplate.update("""
            UPDATE category_family_config
            SET metadata = metadata || '{"launch":true}'::jsonb
            WHERE family_key = 'silk_saree'
            """);

        adminCategoryService.updateFamilyMerchandising(
            "silk_saree",
            List.of(Map.of("value", " PREMIUM ", "label", " Premium ", "icon", " Gem ")),
            List.of(Map.of("value", " COLOR_RED ", "label", " Red "))
        );

        String metadata = jdbcTemplate.queryForObject(
            "SELECT metadata::text FROM category_family_config WHERE family_key = 'silk_saree'",
            String.class
        );
        assertThat(metadata).contains("\"launch\": true", "\"merchandisingTags\"", "\"colorFilters\"");
        assertThat(metadata).contains("\"value\": \"PREMIUM\"", "\"icon\": \"Gem\"", "\"value\": \"COLOR_RED\"");
        assertThat(metadata).doesNotContain(" PREMIUM ", " Gem ", " COLOR_RED ");
        }

    private Map<String, Long> tableCounts(List<String> tableNames) {
        return tableNames.stream().collect(java.util.stream.Collectors.toMap(
                tableName -> tableName,
                tableName -> jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableName, Long.class)
        ));
    }

    private List<String> familyKeys(JsonNode data) {
        List<String> keys = new ArrayList<>();
        data.forEach(node -> keys.add(node.path("familyKey").asText()));
        return keys;
    }
}
