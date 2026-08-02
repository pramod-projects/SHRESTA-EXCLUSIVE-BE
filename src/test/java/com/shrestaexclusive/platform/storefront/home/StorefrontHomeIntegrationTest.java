package com.shrestaexclusive.platform.storefront.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shresta.media.asset-base-url=http://localhost:9010/shresta-local-assets"
)
class StorefrontHomeIntegrationTest {

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shresta")
            .withUsername("shresta_app")
            .withPassword("change-me");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void returnsDatabaseSeededMultiCategoryStorefrontHome() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/storefront/home", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode root = objectMapper.readTree(response.getBody());

        assertThat(root.path("success").asBoolean()).isTrue();
        JsonNode data = root.path("data");
        assertThat(data.path("brand").path("logo").path("url").asText())
                .isEqualTo("http://localhost:9010/shresta-local-assets/shresta-logo.png?v=1");
        assertThat(data.path("featuredCollections"))
                .extracting(node -> node.path("familyKey").asText())
                .containsOnly("silk_saree");
        assertThat(data.path("bestsellers"))
                .extracting(node -> node.path("pricePaise").asLong())
                .contains(1299000L);
        assertThat(data.path("bestsellers")).hasSizeGreaterThanOrEqualTo(73);
        assertThat(data.path("bestsellers"))
                .extracting(node -> node.path("sku").asText())
                .contains("SHRESTA-SILK-0001");
        assertThat(data.path("bestsellers")).isNotEmpty();
        assertThat(data.path("featuredCollections"))
                .filteredOn(node -> node.path("slug").asText().equals("new-arrivals"))
                .singleElement()
                .satisfies(node -> assertThat(node.path("itemCount").asInt()).isGreaterThan(8));
        assertThat(data.toString()).doesNotContain("data:image");
    }
}
