package com.shrestaexclusive.platform.payment.razorpay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.order.CustomerOrderLifecycleService;

@Service
public class RazorpayWebhookService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RazorpayWebhookProperties properties;
    private final CustomerOrderLifecycleService lifecycleService;
    private final Clock clock;

    @Autowired
    public RazorpayWebhookService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RazorpayWebhookProperties properties,
            CustomerOrderLifecycleService lifecycleService
    ) {
        this(jdbcTemplate, objectMapper, properties, lifecycleService, Clock.systemUTC());
    }

    RazorpayWebhookService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RazorpayWebhookProperties properties,
            CustomerOrderLifecycleService lifecycleService,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.lifecycleService = lifecycleService;
        this.clock = clock;
    }

    @Transactional
    public RazorpayWebhookResult process(String payload, String signature, String eventIdHeader) {
        if (!properties.isActive()) {
            return new RazorpayWebhookResult(true, "IGNORED", "unknown", trimmed(eventIdHeader), null,
                    "Razorpay webhook is disabled by configuration.");
        }

        String secret = trimmed(properties.getSecret());
        if (secret == null) {
            throw new RazorpayWebhookValidationException("Razorpay webhook secret is not configured.");
        }

        String normalizedPayload = payload == null ? "" : payload;
        String providedSignature = trimmed(signature);
        if (providedSignature == null) {
            throw new RazorpayWebhookValidationException("Missing X-Razorpay-Signature header.");
        }

        if (!secureEquals(hexHmacSha256(secret, normalizedPayload), providedSignature.toLowerCase(Locale.ROOT))) {
            throw new RazorpayWebhookValidationException("Invalid Razorpay webhook signature.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(normalizedPayload);
        } catch (JsonProcessingException exception) {
            throw new RazorpayWebhookValidationException("Webhook payload is not valid JSON.");
        }

        String eventType = text(root, "event");
        if (eventType == null) {
            throw new RazorpayWebhookValidationException("Webhook payload is missing event type.");
        }

        String eventId = trimmed(eventIdHeader);
        if (eventId == null) {
            eventId = text(root, "payload", "payment", "entity", "id");
            if (eventId != null) {
                eventId = eventType + ":" + eventId;
            }
        }

        String paymentId = text(root, "payload", "payment", "entity", "id");
        String orderNumber = resolveOrderNumber(root);

        if (eventId != null && isDuplicateEventId(eventId)) {
            return new RazorpayWebhookResult(true, "IGNORED", eventType, eventId, orderNumber,
                    "Duplicate provider event id ignored.");
        }

        upsertPaymentTransaction(root, eventType, eventId, paymentId, orderNumber);

        UUID webhookRowId = createWebhookEvent(eventType, eventId, paymentId, orderNumber, providedSignature, root);

        if (orderNumber == null) {
            markWebhookEvent(webhookRowId, "IGNORED", "Order number not found in webhook payload notes/receipt.");
            return new RazorpayWebhookResult(true, "IGNORED", eventType, eventId, null,
                    "Order number not found in webhook payload notes/receipt.");
        }

        if (!isOrderEventSupported(eventType)) {
            markWebhookEvent(webhookRowId, "IGNORED", "Event type is not mapped to order status updates.");
            return new RazorpayWebhookResult(true, "IGNORED", eventType, eventId, orderNumber,
                    "Event type is not mapped to order status updates.");
        }

        try {
            CustomerOrderLifecycleService.PaymentWebhookOrderUpdateResult result = lifecycleService.applyRazorpayPaymentWebhook(
                    orderNumber,
                    eventType,
                    paymentId,
                    eventId
            );

            markWebhookEvent(webhookRowId, result.changed() ? "PROCESSED" : "IGNORED", result.message());
            return new RazorpayWebhookResult(
                    true,
                    result.changed() ? "PROCESSED" : "IGNORED",
                    eventType,
                    eventId,
                    orderNumber,
                    result.message()
            );
        } catch (RuntimeException exception) {
            markWebhookEvent(webhookRowId, "FAILED", trimmed(exception.getMessage()));
            throw exception;
        }
    }

    private boolean isOrderEventSupported(String eventType) {
        return "payment.authorized".equals(eventType)
                || "payment.captured".equals(eventType)
                || "payment.failed".equals(eventType)
                || "payment.refunded".equals(eventType);
    }

    private UUID createWebhookEvent(
            String eventType,
            String eventId,
            String paymentId,
            String orderNumber,
            String signature,
            JsonNode payloadJson
    ) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadJson);
        } catch (JsonProcessingException exception) {
            payload = "{}";
        }

        return jdbcTemplate.queryForObject("""
                INSERT INTO customer_payment_webhook_events (
                    provider,
                    provider_event_id,
                    event_type,
                    payment_id,
                    order_number,
                    signature,
                    payload_json,
                    status,
                    created_at,
                    updated_at
                )
                VALUES (
                    'RAZORPAY',
                    :providerEventId,
                    :eventType,
                    :paymentId,
                    :orderNumber,
                    :signature,
                    CAST(:payloadJson AS jsonb),
                    'RECEIVED',
                    :createdAt,
                    :createdAt
                )
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("providerEventId", eventId)
                .addValue("eventType", eventType)
                .addValue("paymentId", paymentId)
                .addValue("orderNumber", orderNumber)
                .addValue("signature", signature)
                .addValue("payloadJson", payload)
                .addValue("createdAt", Timestamp.from(Instant.now(clock))), UUID.class);
    }

    private boolean isDuplicateEventId(String eventId) {
        if (eventId == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)::int
                FROM customer_payment_webhook_events
                WHERE provider = 'RAZORPAY'
                  AND provider_event_id = :providerEventId
                """, new MapSqlParameterSource("providerEventId", eventId), Integer.class);
        return count != null && count > 0;
    }

    private void markWebhookEvent(UUID eventId, String status, String reason) {
        jdbcTemplate.update("""
                UPDATE customer_payment_webhook_events
                SET status = :status,
                    failure_reason = :failureReason,
                    processed_at = :processedAt,
                    updated_at = :processedAt
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("failureReason", truncate(reason, 500))
                .addValue("processedAt", Timestamp.from(Instant.now(clock)))
                .addValue("id", eventId));
    }

    private void upsertPaymentTransaction(
            JsonNode root,
            String eventType,
            String eventId,
            String paymentId,
            String orderNumber
    ) {
        if (paymentId == null) {
            return;
        }

        String providerOrderId = text(root, "payload", "payment", "entity", "order_id");
        String paymentMethod = text(root, "payload", "payment", "entity", "method");
        String currency = text(root, "payload", "payment", "entity", "currency");
        String payloadStatus = text(root, "payload", "payment", "entity", "status");
        String paymentStatus = mapPaymentStatus(eventType, payloadStatus);

        Long amountMinor = longValue(root, "payload", "payment", "entity", "amount");
        Boolean capturedFlag = booleanValue(root, "payload", "payment", "entity", "captured");
        Timestamp now = Timestamp.from(Instant.now(clock));
        Timestamp capturedAt = "CAPTURED".equals(paymentStatus) ? now : null;

        jdbcTemplate.update("""
                INSERT INTO customer_payment_transactions (
                    provider,
                    payment_id,
                    provider_order_id,
                    order_number,
                    latest_event_type,
                    latest_provider_event_id,
                    payment_method,
                    payment_status,
                    amount_minor,
                    currency,
                    is_captured,
                    captured_at,
                    last_webhook_received_at,
                    created_at,
                    updated_at
                )
                VALUES (
                    'RAZORPAY',
                    :paymentId,
                    :providerOrderId,
                    :orderNumber,
                    :eventType,
                    :eventId,
                    :paymentMethod,
                    :paymentStatus,
                    :amountMinor,
                    :currency,
                    :isCaptured,
                    :capturedAt,
                    :lastWebhookReceivedAt,
                    :createdAt,
                    :updatedAt
                )
                ON CONFLICT (provider, payment_id)
                DO UPDATE SET
                    provider_order_id = COALESCE(EXCLUDED.provider_order_id, customer_payment_transactions.provider_order_id),
                    order_number = COALESCE(EXCLUDED.order_number, customer_payment_transactions.order_number),
                    latest_event_type = EXCLUDED.latest_event_type,
                    latest_provider_event_id = COALESCE(EXCLUDED.latest_provider_event_id, customer_payment_transactions.latest_provider_event_id),
                    payment_method = COALESCE(EXCLUDED.payment_method, customer_payment_transactions.payment_method),
                    payment_status = EXCLUDED.payment_status,
                    amount_minor = COALESCE(EXCLUDED.amount_minor, customer_payment_transactions.amount_minor),
                    currency = COALESCE(EXCLUDED.currency, customer_payment_transactions.currency),
                    is_captured = COALESCE(EXCLUDED.is_captured, customer_payment_transactions.is_captured),
                    captured_at = COALESCE(EXCLUDED.captured_at, customer_payment_transactions.captured_at),
                    last_webhook_received_at = EXCLUDED.last_webhook_received_at,
                    updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource()
                .addValue("paymentId", paymentId)
                .addValue("providerOrderId", providerOrderId)
                .addValue("orderNumber", orderNumber)
                .addValue("eventType", eventType)
                .addValue("eventId", eventId)
                .addValue("paymentMethod", paymentMethod)
                .addValue("paymentStatus", paymentStatus)
                .addValue("amountMinor", amountMinor)
                .addValue("currency", currency)
                .addValue("isCaptured", capturedFlag)
                .addValue("capturedAt", capturedAt)
                .addValue("lastWebhookReceivedAt", now)
                .addValue("createdAt", now)
                .addValue("updatedAt", now));
    }

    private static String mapPaymentStatus(String eventType, String payloadStatus) {
        if ("payment.captured".equals(eventType)) {
            return "CAPTURED";
        }
        if ("payment.failed".equals(eventType)) {
            return "FAILED";
        }
        if ("payment.refunded".equals(eventType)) {
            return "REFUNDED";
        }
        if ("payment.authorized".equals(eventType)) {
            return "AUTHORIZED";
        }
        if (payloadStatus == null) {
            return "UNKNOWN";
        }
        return switch (payloadStatus.trim().toLowerCase(Locale.ROOT)) {
            case "captured" -> "CAPTURED";
            case "failed" -> "FAILED";
            case "authorized" -> "AUTHORIZED";
            case "refunded" -> "REFUNDED";
            default -> "UNKNOWN";
        };
    }

    private static Long longValue(JsonNode root, String... path) {
        JsonNode node = node(root, path);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.canConvertToLong()) {
            return null;
        }
        return node.longValue();
    }

    private static Boolean booleanValue(JsonNode root, String... path) {
        JsonNode node = node(root, path);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            return null;
        }
        return node.booleanValue();
    }

    private static JsonNode node(JsonNode root, String... path) {
        JsonNode current = root;
        for (String key : path) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
            current = current.get(key);
        }
        return current;
    }

    private static String resolveOrderNumber(JsonNode root) {
        String fromPaymentNote = text(root, "payload", "payment", "entity", "notes", "order_number");
        if (fromPaymentNote != null) {
            return fromPaymentNote.trim().toUpperCase(Locale.ROOT);
        }
        String fromPaymentCamel = text(root, "payload", "payment", "entity", "notes", "orderNumber");
        if (fromPaymentCamel != null) {
            return fromPaymentCamel.trim().toUpperCase(Locale.ROOT);
        }
        String fromOrderReceipt = text(root, "payload", "order", "entity", "receipt");
        if (fromOrderReceipt != null) {
            return fromOrderReceipt.trim().toUpperCase(Locale.ROOT);
        }
        String fromPaymentDescription = text(root, "payload", "payment", "entity", "description");
        if (fromPaymentDescription != null && fromPaymentDescription.toUpperCase(Locale.ROOT).contains("SHRESTA-")) {
            return fromPaymentDescription.trim().toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static String text(JsonNode root, String... path) {
        JsonNode node = root;
        for (String key : path) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            node = node.get(key);
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String hexHmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to verify Razorpay webhook signature.", exception);
        }
    }

    private static boolean secureEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String out = value.trim();
        return out.isEmpty() ? null : out;
    }
}
