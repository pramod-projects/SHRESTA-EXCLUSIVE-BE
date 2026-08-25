package com.shrestaexclusive.platform.email.worker;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.email.domain.EmailProviderResult;
import com.shrestaexclusive.platform.email.domain.NotificationType;
import com.shrestaexclusive.platform.email.domain.ProviderAttempt;

@Repository
public class EmailOutboxRepository {
    private static final TypeReference<Map<String, String>> VARIABLES = new TypeReference<>() {};
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    public EmailOutboxRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<EmailOutboxItem> claim(String owner, int limit, Duration lease) {
        Instant now = Instant.now(clock);
        return jdbcClient.sql("""
                WITH candidates AS (
                    SELECT id FROM email_outbox
                    WHERE ((state IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= :now)
                        OR (state = 'PROCESSING' AND lease_expires_at < :now))
                      AND expires_at > :now
                    ORDER BY priority, created_at
                    FOR UPDATE SKIP LOCKED LIMIT :limit
                )
                UPDATE email_outbox outbox SET state = 'PROCESSING', lease_owner = :owner,
                    lease_expires_at = :leaseExpires, lease_generation = lease_generation + 1, updated_at = :now
                FROM candidates WHERE outbox.id = candidates.id
                RETURNING outbox.*
                """)
                .param("now", Timestamp.from(now)).param("limit", limit).param("owner", owner)
                .param("leaseExpires", Timestamp.from(now.plus(lease)))
                .query((rs, rowNum) -> new EmailOutboxItem(rs.getObject("id", UUID.class),
                        NotificationType.valueOf(rs.getString("notification_type")), rs.getString("recipient_email"),
                        variables(rs.getString("template_variables")),
                        rs.getString("idempotency_key"), rs.getInt("attempt_count"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("lease_owner"), rs.getLong("lease_generation")))
                .list();
    }

    private Map<String, String> variables(String json) {
        try {
            return objectMapper.readValue(json, VARIABLES);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid email outbox template variables.", exception);
        }
    }

    @Transactional
    public void complete(EmailOutboxItem item, List<ProviderAttempt> attempts, Instant nextAttempt) {
        int attempt = item.attemptCount() + 1;
        ProviderAttempt finalAttempt = attempts.get(attempts.size() - 1);
        String provider = "NONE".equals(finalAttempt.provider()) ? null : finalAttempt.provider();
        EmailProviderResult result = finalAttempt.result();

        String state = switch (result.outcome()) {
            case ACCEPTED -> "PROVIDER_ACCEPTED";
            case TRANSIENT_FAILURE -> nextAttempt == null ? "FAILED" : "RETRY_WAIT";
            case OUTCOME_UNKNOWN -> "OUTCOME_UNKNOWN";
            case PERMANENT_FAILURE, CONFIGURATION_FAILURE -> "FAILED";
        };
        Instant now = Instant.now(clock);
        int updated = jdbcClient.sql("""
                UPDATE email_outbox SET state = :state, attempt_count = :attempt, provider_used = :provider,
                    provider_message_id = :messageId, last_failure_class = :failure,
                    next_attempt_at = COALESCE(:nextAttempt, next_attempt_at), lease_owner = NULL,
                    lease_expires_at = NULL, accepted_at = CASE WHEN :state = 'PROVIDER_ACCEPTED' THEN :now ELSE accepted_at END,
                    template_variables = CASE WHEN notification_type = 'OTP' AND :state <> 'RETRY_WAIT' THEN '{}'::jsonb ELSE template_variables END,
                    updated_at = :now WHERE id = :id AND state = 'PROCESSING'
                    AND lease_owner = :leaseOwner AND lease_generation = :leaseGeneration
                """).param("state", state).param("attempt", attempt).param("provider", provider)
                .param("messageId", result.providerMessageId()).param("failure", result.failureClass())
                .param("nextAttempt", nextAttempt == null ? null : Timestamp.from(nextAttempt)).param("now", Timestamp.from(now))
                .param("id", item.id()).param("leaseOwner", item.leaseOwner())
                .param("leaseGeneration", item.leaseGeneration()).update();
        if (updated != 1) {
            throw new IllegalStateException("Email outbox lease is no longer owned by this worker.");
        }

        for (ProviderAttempt providerAttempt : attempts) {
            EmailProviderResult attemptResult = providerAttempt.result();
            jdbcClient.sql("""
                INSERT INTO email_delivery_attempts (outbox_id, attempt_number, provider, provider_idempotency_key,
                    outcome, http_status, provider_message_id, failure_class, retryable, latency_ms)
                VALUES (:id, :attempt, :provider, :providerKey, :outcome, :status, :messageId, :failure,
                    :retryable, :latency)
                """).param("id", item.id()).param("attempt", attempt).param("provider", providerAttempt.provider())
                .param("providerKey", item.idempotencyKey())
                .param("outcome", attemptResult.outcome().name()).param("status", attemptResult.httpStatus())
                .param("messageId", attemptResult.providerMessageId()).param("failure", attemptResult.failureClass())
                .param("retryable", attemptResult.outcome().name().equals("TRANSIENT_FAILURE")).param("latency", attemptResult.latencyMs()).update();
        }
    }

    @Transactional
    public MaintenanceResult maintain(Duration terminalRetention, Duration webhookRetention, Duration unknownAlertAge) {
        Instant now = Instant.now(clock);
        int expiredOutboxRows = jdbcClient.sql("""
            UPDATE email_outbox SET state = 'FAILED', last_failure_class = 'NOTIFICATION_EXPIRED',
                lease_owner = NULL, lease_expires_at = NULL,
                template_variables = CASE WHEN notification_type = 'OTP' THEN '{}'::jsonb ELSE template_variables END,
                updated_at = :now
            WHERE expires_at <= :now
              AND (state IN ('PENDING','RETRY_WAIT')
                OR (state = 'PROCESSING' AND lease_expires_at < :now))
            """).param("now", Timestamp.from(now)).update();
        int scrubbedOtpRows = jdbcClient.sql("""
            UPDATE email_outbox SET template_variables = '{}'::jsonb, updated_at = :now
            WHERE notification_type = 'OTP' AND expires_at <= :now AND template_variables <> '{}'::jsonb
            """).param("now", Timestamp.from(now)).update();
        int deletedOutboxRows = jdbcClient.sql("""
            DELETE FROM email_outbox
            WHERE state IN ('PROVIDER_ACCEPTED','DELIVERED','FAILED','BOUNCED','REJECTED','COMPLAINT')
              AND updated_at < :cutoff
            """).param("cutoff", Timestamp.from(now.minus(terminalRetention))).update();
        int deletedWebhookRows = jdbcClient.sql("DELETE FROM email_webhook_events WHERE created_at < :cutoff")
            .param("cutoff", Timestamp.from(now.minus(webhookRetention))).update();
        long agedUnknownRows = jdbcClient.sql("""
            SELECT count(*) FROM email_outbox
            WHERE state = 'OUTCOME_UNKNOWN' AND updated_at < :cutoff
            """).param("cutoff", Timestamp.from(now.minus(unknownAlertAge))).query(Long.class).single();
        return new MaintenanceResult(expiredOutboxRows, scrubbedOtpRows, deletedOutboxRows,
                deletedWebhookRows, agedUnknownRows);
    }

    public record MaintenanceResult(int expiredOutboxRows, int scrubbedOtpRows, int deletedOutboxRows,
            int deletedWebhookRows, long agedUnknownRows) {}
}