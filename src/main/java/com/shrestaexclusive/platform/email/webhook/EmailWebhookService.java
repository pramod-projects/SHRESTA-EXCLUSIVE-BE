package com.shrestaexclusive.platform.email.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class EmailWebhookService {
    private final JdbcClient jdbcClient; private final MeterRegistry metrics;
    public EmailWebhookService(JdbcClient jdbcClient, MeterRegistry metrics) { this.jdbcClient = jdbcClient; this.metrics = metrics; }

    @Transactional
    public boolean accept(String provider, String eventId, String messageId, NormalizedEmailEvent event, Instant occurredAt, String rawBody) {
        String digest = sha256(rawBody);
        UUID insertedId = jdbcClient.sql("""
                INSERT INTO email_webhook_events (provider, provider_event_id, provider_message_id, event_type,
                    payload_digest, occurred_at, processing_state)
                VALUES (:provider, :eventId, :messageId, :event, :digest, :occurredAt, 'PENDING')
                ON CONFLICT (provider, provider_event_id) DO NOTHING
                RETURNING id
                """).param("provider", provider).param("eventId", eventId).param("messageId", messageId)
                .param("event", event.name()).param("digest", digest).param("occurredAt", Timestamp.from(occurredAt))
                .query((rs, rowNum) -> rs.getObject("id", UUID.class)).optional().orElse(null);
        if (insertedId == null) return false;
        apply(new PendingEvent(insertedId, provider, messageId, event, occurredAt));
        metrics.counter("shresta.email.webhook", "provider", provider, "event", event.name()).increment();
        return true;
    }

    @Scheduled(fixedDelayString = "${shresta.email.webhook-reconcile-delay-ms:5000}")
    @Transactional
    public void reconcilePending() {
        List<PendingEvent> events = jdbcClient.sql("""
                SELECT id, provider, provider_message_id, event_type, occurred_at
                FROM email_webhook_events
                WHERE processing_state = 'PENDING'
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 100
                """).query((rs, rowNum) -> new PendingEvent(rs.getObject("id", UUID.class),
                        rs.getString("provider"), rs.getString("provider_message_id"),
                        NormalizedEmailEvent.valueOf(rs.getString("event_type")),
                        rs.getTimestamp("occurred_at").toInstant())).list();
        events.forEach(this::apply);
    }

    private void apply(PendingEvent pendingEvent) {
        String provider = pendingEvent.provider();
        String messageId = pendingEvent.messageId();
        NormalizedEmailEvent event = pendingEvent.event();
        Instant occurredAt = pendingEvent.occurredAt();
        String state = switch (event) {
            case ACCEPTED, DEFERRED -> "PROVIDER_ACCEPTED"; case DELIVERED -> "DELIVERED";
            case BOUNCED -> "BOUNCED"; case REJECTED -> "REJECTED"; case COMPLAINT -> "COMPLAINT";
        };
        jdbcClient.sql("""
                UPDATE email_outbox SET state = :state,
                    delivered_at = CASE WHEN :state = 'DELIVERED' THEN :occurredAt ELSE delivered_at END,
                    updated_at = now() WHERE provider_used = :provider
                    AND (provider_message_id = :messageId OR idempotency_key = :messageId)
                    AND (state NOT IN ('DELIVERED','BOUNCED','REJECTED','COMPLAINT') OR :state = 'COMPLAINT')
                """).param("state", state).param("occurredAt", Timestamp.from(occurredAt))
                .param("provider", provider).param("messageId", messageId).update();
            boolean correlated = jdbcClient.sql("""
                SELECT EXISTS (SELECT 1 FROM email_outbox
                    WHERE provider_used = :provider
                      AND (provider_message_id = :messageId OR idempotency_key = :messageId))
                """).param("provider", provider).param("messageId", messageId).query(Boolean.class).single();
            if (correlated) {
                jdbcClient.sql("""
                    UPDATE email_webhook_events
                    SET processing_state = 'PROCESSED', processed_at = now()
                    WHERE id = :id AND processing_state = 'PENDING'
                    """).param("id", pendingEvent.id()).update();
            }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
    private record PendingEvent(UUID id, String provider, String messageId,
            NormalizedEmailEvent event, Instant occurredAt) {}
    public enum NormalizedEmailEvent { ACCEPTED, DELIVERED, DEFERRED, BOUNCED, REJECTED, COMPLAINT }
}