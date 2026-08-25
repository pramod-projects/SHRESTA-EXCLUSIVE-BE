package com.shrestaexclusive.platform.sms.webhook;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "shresta.media.asset-base-url=http://localhost:9010/shresta-local-assets",
                "shresta.sms.spring-edge-webhook-auth-enabled=true",
                "shresta.sms.spring-edge-webhook-auth-header=x-springedge-webhook-token",
            "shresta.sms.spring-edge-webhook-auth-token=test-springedge-token",
            "shresta.sms.spring-edge-webhook-signature-enabled=true",
            "shresta.sms.spring-edge-webhook-signature-header=x-springedge-signature",
            "shresta.sms.spring-edge-webhook-signature-secret=test-springedge-signing-secret",
            "shresta.sms.msg91-webhook-auth-enabled=true",
            "shresta.sms.msg91-webhook-auth-header=x-msg91-webhook-token",
            "shresta.sms.msg91-webhook-auth-token=test-msg91-token"
        }
)
class SmsProviderWebhookAuthIntegrationTest {

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
    void missingWebhookTokenReturnsUnauthorized() throws Exception {
        String payload = "{\"event\":\"message.delivered\",\"status\":\"DELIVERED\"}";
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/sms/providers/springedge/webhook",
                HttpMethod.POST,
            webhookRequest(payload, "x-springedge-webhook-token", null),
                String.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("success").asBoolean()).isFalse();
        assertThat(root.path("error").path("code").asText()).isEqualTo("SMS_WEBHOOK_UNAUTHORIZED");
    }

    @Test
    void validWebhookTokenAllowsProcessing() throws Exception {
        String payload = "{\"event\":\"message.delivered\",\"status\":\"DELIVERED\"}";
        String signature = hmacSha256Hex("test-springedge-signing-secret", payload);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/sms/providers/springedge/webhook",
                HttpMethod.POST,
            webhookRequest(payload,
                "x-springedge-webhook-token", "test-springedge-token",
                "x-springedge-signature", "sha256=" + signature),
                String.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("data").path("provider").asText()).isEqualTo("SPRINGEDGE");
        assertThat(root.path("data").path("processingStatus").asText()).isEqualTo("RECEIVED");
    }

    @Test
    void invalidWebhookSignatureIsRejected() throws Exception {
        String payload = "{\"event\":\"message.delivered\",\"status\":\"DELIVERED\"}";
        ResponseEntity<String> denied = restTemplate.exchange(
                "/api/v1/sms/providers/springedge/webhook",
                HttpMethod.POST,
                webhookRequest(payload,
                        "x-springedge-webhook-token", "test-springedge-token",
                        "x-springedge-signature", "sha256=bad-signature"),
                String.class
        );

        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        JsonNode root = objectMapper.readTree(denied.getBody());
        assertThat(root.path("success").asBoolean()).isFalse();
        assertThat(root.path("error").path("code").asText()).isEqualTo("SMS_WEBHOOK_UNAUTHORIZED");
    }

    @Test
    void msg91WebhookRequiresConfiguredTokenHeader() throws Exception {
        String payload = "{\"type\":\"message.failed\",\"status\":\"FAILED\"}";

        ResponseEntity<String> denied = restTemplate.exchange(
                "/api/v1/sms/providers/msg91/webhook",
                HttpMethod.POST,
                webhookRequest(payload, "x-msg91-webhook-token", null),
                String.class
        );
        assertThat(denied.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> allowed = restTemplate.exchange(
                "/api/v1/sms/providers/msg91/webhook",
                HttpMethod.POST,
                webhookRequest(payload, "x-msg91-webhook-token", "test-msg91-token"),
                String.class
        );

        assertThat(allowed.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode root = objectMapper.readTree(allowed.getBody());
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("data").path("provider").asText()).isEqualTo("MSG91");
        assertThat(root.path("data").path("processingStatus").asText()).isEqualTo("RECEIVED");
    }

    private static HttpEntity<String> webhookRequest(String payload, String headerName, String token) {
        return webhookRequest(payload, headerName, token, null, null);
    }

    private static HttpEntity<String> webhookRequest(
            String payload,
            String headerName,
            String token,
            String signatureHeaderName,
            String signature
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set(headerName, token);
        }
        if (signatureHeaderName != null && signature != null) {
            headers.set(signatureHeaderName, signature);
        }
        return new HttpEntity<>(payload, headers);
    }

    private static String hmacSha256Hex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
