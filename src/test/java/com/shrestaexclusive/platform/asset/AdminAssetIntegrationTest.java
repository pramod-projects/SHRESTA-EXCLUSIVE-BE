package com.shrestaexclusive.platform.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shresta.media.asset-base-url=http://localhost:9010/shresta-local-assets"
)
class AdminAssetIntegrationTest {

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
                .contains("hero-silk-saree-maroon-gold")
                .doesNotContain("shresta-logo");
        assertThat(firstAsset.path("assetUrl").asText())
                .startsWith("http://localhost:9010/shresta-local-assets/")
                .doesNotContain("/shresta-media", "/shresta-assets");
    }
}
