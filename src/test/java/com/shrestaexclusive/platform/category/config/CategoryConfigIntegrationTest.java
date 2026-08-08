package com.shrestaexclusive.platform.category.config;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Testcontainers
@ActiveProfiles("uat")
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

    private List<String> familyKeys(JsonNode data) {
        List<String> keys = new ArrayList<>();
        data.forEach(node -> keys.add(node.path("familyKey").asText()));
        return keys;
    }
}
