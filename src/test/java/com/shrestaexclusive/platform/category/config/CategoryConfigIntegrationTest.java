package com.shrestaexclusive.platform.category.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
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
class CategoryConfigIntegrationTest {

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
    void returnsFlywaySeededCategoryConfiguration() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/categories", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("success").asBoolean()).isTrue();

        JsonNode data = root.path("data");
        assertThat(data).hasSize(1);
        assertThat(familyKeys(data)).containsExactly("silk_saree");
        assertThat(data.get(0).path("productTypes")).isNotEmpty();
        assertThat(data.get(0).path("attributes")).isNotEmpty();
        assertThat(data.get(0).path("filters").get(0).path("backendMapping").asText())
                .startsWith("attribute_facets.");
    }

    private List<String> familyKeys(JsonNode data) {
        List<String> keys = new ArrayList<>();
        data.forEach(node -> keys.add(node.path("familyKey").asText()));
        return keys;
    }
}
