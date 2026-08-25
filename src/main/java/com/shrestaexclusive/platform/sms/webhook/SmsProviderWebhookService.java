package com.shrestaexclusive.platform.sms.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.sms.CustomerSmsProperties;

@Service
public class SmsProviderWebhookService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final CustomerSmsProperties properties;
    private final Clock clock;

    @Autowired
    public SmsProviderWebhookService(JdbcClient jdbc, ObjectMapper objectMapper, CustomerSmsProperties properties) {
        this(jdbc, objectMapper, properties, Clock.systemUTC());
    }

    SmsProviderWebhookService(JdbcClient jdbc, ObjectMapper objectMapper, CustomerSmsProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public SmsProviderWebhookResult process(String provider, String payload, Map<String, String> headers) {
        String normalizedProvider = normalizeProvider(provider);
        String normalizedPayload = payload == null ? "{}" : payload;
        validateWebhookAuth(normalizedProvider, headers, normalizedPayload);
        String persistedPayload = normalizedPayload;
        JsonNode root;
        try {
            root = objectMapper.readTree(normalizedPayload);
        } catch (JsonProcessingException exception) {
            root = objectMapper.createObjectNode();
            persistedPayload = "{}";
        }

        String providerEventId = firstNonBlank(
                headers.get("x-event-id"),
                headers.get("x-msg91-event-id"),
                headers.get("x-springedge-event-id"),
                text(root, "event_id"),
                text(root, "eventId")
        );

        String providerMessageId = firstNonBlank(
                headers.get("x-message-id"),
                headers.get("x-msg91-message-id"),
                headers.get("x-springedge-message-id"),
                text(root, "message_id"),
                text(root, "messageId"),
                text(root, "sms_id"),
                text(root, "request_id"),
                text(root, "id")
        );

        String eventType = firstNonBlank(
                text(root, "event"),
                text(root, "type")
        );

        String deliveryStatus = normalizedStatus(firstNonBlank(
                text(root, "status"),
                text(root, "delivery_status"),
                text(root, "deliveryStatus")
        ));

        String mobile = normalizeMobile(firstNonBlank(
                text(root, "to"),
                text(root, "mobile"),
                text(root, "msisdn")
        ));

        Timestamp now = Timestamp.from(Instant.now(clock));
        UUID linkedSmsMessageId = resolveLinkedSmsMessageId(normalizedProvider, providerMessageId);
        String processingStatus = linkedSmsMessageId == null ? "RECEIVED" : "LINKED";

        UUID eventId = jdbc.sql("""
                INSERT INTO customer_sms_webhook_events (
                    provider,
                    provider_event_id,
                    provider_message_id,
                    event_type,
                    delivery_status,
                    mobile_number,
                    linked_sms_message_id,
                    payload_json,
                    processing_status,
                    received_at,
                    created_at,
                    updated_at
                ) VALUES (
                    :provider,
                    :providerEventId,
                    :providerMessageId,
                    :eventType,
                    :deliveryStatus,
                    :mobileNumber,
                    :linkedSmsMessageId,
                    CAST(:payloadJson AS jsonb),
                    :processingStatus,
                    :receivedAt,
                    :receivedAt,
                    :receivedAt
                )
                RETURNING id
                """)
                .param("provider", normalizedProvider)
                .param("providerEventId", providerEventId)
                .param("providerMessageId", providerMessageId)
                .param("eventType", eventType)
                .param("deliveryStatus", deliveryStatus)
                .param("mobileNumber", mobile)
                .param("linkedSmsMessageId", linkedSmsMessageId)
                .param("payloadJson", persistedPayload)
                .param("processingStatus", processingStatus)
                .param("receivedAt", now)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .single();

        if (linkedSmsMessageId == null) {
            return new SmsProviderWebhookResult(
                    normalizedProvider,
                    providerEventId,
                    providerMessageId,
                    eventType,
                    deliveryStatus,
                    null,
                    "RECEIVED",
                    "Webhook persisted. Message link will complete after provider message id is known."
            );
        }

        applyLinkedStatusUpdate(linkedSmsMessageId, deliveryStatus, now);
        jdbc.sql("""
                UPDATE customer_sms_webhook_events
                SET processed_at = :processedAt,
                    processing_status = 'LINKED',
                    updated_at = :processedAt
                WHERE id = :id
                """)
                .param("processedAt", now)
                .param("id", eventId)
                .update();

        return new SmsProviderWebhookResult(
                normalizedProvider,
                providerEventId,
                providerMessageId,
                eventType,
                deliveryStatus,
                linkedSmsMessageId,
                "LINKED",
                "Webhook persisted and linked to customer SMS message."
        );
    }

    private UUID resolveLinkedSmsMessageId(String provider, String providerMessageId) {
        if (!StringUtils.hasText(providerMessageId)) {
            return null;
        }

        return jdbc.sql("""
                SELECT sms_message_id
                FROM customer_sms_attempts
                WHERE provider = :provider
                  AND provider_message_id = :providerMessageId
                ORDER BY attempted_at DESC
                LIMIT 1
                """)
                .param("provider", provider)
                .param("providerMessageId", providerMessageId)
                .query((rs, rowNum) -> rs.getObject("sms_message_id", UUID.class))
                .optional()
                .orElse(null);
    }

    private void applyLinkedStatusUpdate(UUID smsMessageId, String deliveryStatus, Timestamp now) {
        String normalized = normalizedStatus(deliveryStatus);
        if (!StringUtils.hasText(normalized)) {
            return;
        }

        if ("FAILED".equals(normalized) || "UNDELIVERED".equals(normalized) || "REJECTED".equals(normalized)) {
            jdbc.sql("""
                    UPDATE customer_sms_messages
                    SET status = 'FAILED',
                        failure_reason = :reason,
                        updated_at = :updatedAt
                    WHERE id = :id
                    """)
                    .param("reason", "Provider delivery update: " + normalized)
                    .param("updatedAt", now)
                    .param("id", smsMessageId)
                    .update();
            return;
        }

        if ("SENT".equals(normalized) || "DELIVERED".equals(normalized) || "QUEUED".equals(normalized)) {
            jdbc.sql("""
                    UPDATE customer_sms_messages
                    SET status = 'SENT',
                        sent_at = COALESCE(sent_at, :sentAt),
                        updated_at = :sentAt
                    WHERE id = :id
                    """)
                    .param("sentAt", now)
                    .param("id", smsMessageId)
                    .update();
        }
    }

    private static String normalizeProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SPRINGEDGE", "MSG91" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported SMS provider webhook source: " + provider);
        };
    }

    private void validateWebhookAuth(String provider, Map<String, String> headers, String payload) {
        boolean enabled;
        String headerName;
        String expectedToken;
        boolean signatureEnabled;
        String signatureHeaderName;
        String signatureSecret;
        switch (provider) {
            case "SPRINGEDGE" -> {
                enabled = properties.isSpringEdgeWebhookAuthEnabled();
                headerName = firstNonBlank(properties.getSpringEdgeWebhookAuthHeader(), "x-springedge-webhook-token");
                expectedToken = properties.getSpringEdgeWebhookAuthToken();
                signatureEnabled = properties.isSpringEdgeWebhookSignatureEnabled();
                signatureHeaderName = firstNonBlank(properties.getSpringEdgeWebhookSignatureHeader(), "x-springedge-signature");
                signatureSecret = properties.getSpringEdgeWebhookSignatureSecret();
            }
            case "MSG91" -> {
                enabled = properties.isMsg91WebhookAuthEnabled();
                headerName = firstNonBlank(properties.getMsg91WebhookAuthHeader(), "x-msg91-webhook-token");
                expectedToken = properties.getMsg91WebhookAuthToken();
                signatureEnabled = properties.isMsg91WebhookSignatureEnabled();
                signatureHeaderName = firstNonBlank(properties.getMsg91WebhookSignatureHeader(), "x-msg91-signature");
                signatureSecret = properties.getMsg91WebhookSignatureSecret();
            }
            default -> {
                return;
            }
        }

        if (enabled) {
            if (!StringUtils.hasText(expectedToken)) {
                throw new SecurityException("Webhook auth is enabled but token is not configured for provider " + provider + ".");
            }

            String normalizedHeader = headerName.trim().toLowerCase(Locale.ROOT);
            String providedToken = headers.get(normalizedHeader);
            if (!matchesToken(expectedToken, providedToken)) {
                throw new SecurityException("Webhook authentication failed for provider " + provider + ".");
            }
        }

        if (!signatureEnabled) {
            return;
        }

        if (!StringUtils.hasText(signatureSecret)) {
            throw new SecurityException("Webhook signature validation is enabled but secret is not configured for provider " + provider + ".");
        }

        String normalizedSignatureHeader = signatureHeaderName.trim().toLowerCase(Locale.ROOT);
        String providedSignature = normalizeSignature(headers.get(normalizedSignatureHeader));
        if (!StringUtils.hasText(providedSignature)) {
            throw new SecurityException("Webhook signature header is missing for provider " + provider + ".");
        }

        String expectedSignature = hmacSha256Hex(signatureSecret, payload);
        if (!matchesToken(expectedSignature, providedSignature)) {
            throw new SecurityException("Webhook signature validation failed for provider " + provider + ".");
        }
    }

    private static boolean matchesToken(String expectedToken, String providedToken) {
        if (!StringUtils.hasText(providedToken)) {
            return false;
        }
        byte[] expected = expectedToken.trim().getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedToken.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }

    private static String normalizeSignature(String signature) {
        if (!StringUtils.hasText(signature)) {
            return null;
        }
        String normalized = signature.trim();
        if (normalized.regionMatches(true, 0, "sha256=", 0, 7)) {
            return normalized.substring(7).trim().toLowerCase(Locale.ROOT);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format(Locale.ROOT, "%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new SecurityException("Webhook signature digest could not be computed.");
        }
    }

    private static String normalizeMobile(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        return digits;
    }

    private static String normalizedStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode root, String field) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
