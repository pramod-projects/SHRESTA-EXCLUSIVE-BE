package com.shrestaexclusive.platform.sms.webhook;

import java.util.Map;
import java.util.UUID;

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
import org.springframework.jdbc.core.JdbcTemplate;
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
                "shresta.sms.spring-edge-webhook-auth-enabled=false",
                "shresta.sms.spring-edge-webhook-signature-enabled=false",
                "shresta.sms.msg91-webhook-auth-enabled=false",
                "shresta.sms.msg91-webhook-signature-enabled=false"
        }
)
class SmsProviderWebhookIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void springEdgeWebhookLinksMessageAndMarksSent() throws Exception {
        UUID customerId = seededCustomerId();
        UUID smsMessageId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO customer_sms_messages (
                    id, customer_id, mobile_number, purpose, message_body, status, provider_priority
                ) VALUES (?, ?, ?, ?, ?, 'PENDING', 'SPRINGEDGE,MSG91')
                """, smsMessageId, customerId, "9876543210", "REGISTRATION_OTP", "OTP 123456");

        jdbcTemplate.update("""
                INSERT INTO customer_sms_attempts (
                    sms_message_id, customer_id, provider, status, provider_message_id, attempted_at
                ) VALUES (?, ?, 'SPRINGEDGE', 'SUCCESS', ?, now())
                """, smsMessageId, customerId, "msg_link_1");

        String payload = """
                {
                  "event":"message.delivered",
                  "message_id":"msg_link_1",
                  "status":"DELIVERED",
                  "to":"919876543210"
                }
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/sms/providers/springedge/webhook",
                HttpMethod.POST,
                webhookRequest(payload),
                String.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("data").path("processingStatus").asText()).isEqualTo("LINKED");
        assertThat(root.path("data").path("provider").asText()).isEqualTo("SPRINGEDGE");

        String messageStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM customer_sms_messages WHERE id = ?",
                String.class,
                smsMessageId
        );
        assertThat(messageStatus).isEqualTo("SENT");

        Map<String, Object> webhookRow = jdbcTemplate.queryForMap("""
                SELECT provider, provider_message_id, processing_status, linked_sms_message_id
                FROM customer_sms_webhook_events
                WHERE provider = 'SPRINGEDGE' AND provider_message_id = 'msg_link_1'
                ORDER BY received_at DESC
                LIMIT 1
                """);

        assertThat(webhookRow)
                .containsEntry("provider", "SPRINGEDGE")
                .containsEntry("provider_message_id", "msg_link_1")
                .containsEntry("processing_status", "LINKED");
        assertThat(webhookRow.get("linked_sms_message_id")).isEqualTo(smsMessageId);
    }

    @Test
    void msg91WebhookLinksMessageAndMarksFailed() throws Exception {
        UUID customerId = seededCustomerId();
        UUID smsMessageId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO customer_sms_messages (
                    id, customer_id, mobile_number, purpose, message_body, status, provider_priority
                ) VALUES (?, ?, ?, ?, ?, 'PENDING', 'SPRINGEDGE,MSG91')
                """, smsMessageId, customerId, "9876543210", "REGISTRATION_OTP", "OTP 654321");

        jdbcTemplate.update("""
                INSERT INTO customer_sms_attempts (
                    sms_message_id, customer_id, provider, status, provider_message_id, attempted_at
                ) VALUES (?, ?, 'MSG91', 'SUCCESS', ?, now())
                """, smsMessageId, customerId, "req_msg91_fail");

        String payload = """
                {
                  "request_id":"req_msg91_fail",
                  "status":"failed",
                  "type":"message.failed",
                  "mobile":"919876543210"
                }
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/sms/providers/msg91/webhook",
                HttpMethod.POST,
                webhookRequest(payload),
                String.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("data").path("processingStatus").asText()).isEqualTo("LINKED");
        assertThat(root.path("data").path("provider").asText()).isEqualTo("MSG91");

        Map<String, Object> messageRow = jdbcTemplate.queryForMap(
                "SELECT status, failure_reason FROM customer_sms_messages WHERE id = ?",
                smsMessageId
        );
        assertThat(messageRow.get("status")).isEqualTo("FAILED");
        assertThat(String.valueOf(messageRow.get("failure_reason"))).contains("FAILED");
    }

    @Test
    void invalidProviderReturnsBadRequest() throws Exception {
        String payload = "{\"status\":\"DELIVERED\"}";
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/sms/providers/unknown/webhook",
                HttpMethod.POST,
                webhookRequest(payload),
                String.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("success").asBoolean()).isFalse();
        assertThat(root.path("error").path("code").asText()).isEqualTo("INVALID_SMS_WEBHOOK_PROVIDER");
    }

    private UUID seededCustomerId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM customer_accounts WHERE primary_email = 'testuser@gmail.com' LIMIT 1",
                UUID.class
        );
    }

    private static HttpEntity<String> webhookRequest(String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Event-Id", UUID.randomUUID().toString());
        return new HttpEntity<>(payload, headers);
    }
}
