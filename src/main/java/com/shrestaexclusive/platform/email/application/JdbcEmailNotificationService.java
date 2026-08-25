package com.shrestaexclusive.platform.email.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class JdbcEmailNotificationService implements EmailNotificationService {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern HEADER_BREAK = Pattern.compile("[\\r\\n]");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public JdbcEmailNotificationService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this(jdbcClient, objectMapper, Clock.systemUTC());
    }

    JdbcEmailNotificationService(JdbcClient jdbcClient, ObjectMapper objectMapper, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Optional<UUID> enqueue(EmailNotificationCommand command) {
        validate(command);
        boolean enabled = jdbcClient.sql("SELECT enabled FROM notification_configuration WHERE notification_type = :type")
                .param("type", command.type().name())
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Missing notification configuration for " + command.type()));
        if (!enabled) {
            return Optional.empty();
        }

        Instant now = Instant.now(clock);
    String recipient = command.recipient().trim().toLowerCase(Locale.ROOT);
    UUID outboxId = jdbcClient.sql("""
                INSERT INTO email_outbox (
                    notification_type, recipient_email, template_variables, template_version,
                    idempotency_key, customer_id, correlation_id, state, next_attempt_at,
                    expires_at, created_at, updated_at
                ) VALUES (
                    :type, :recipient, CAST(:variables AS jsonb), 1,
                    :idempotencyKey, :customerId, :correlationId, 'PENDING', :now,
                    :expiresAt, :now, :now
                )
                ON CONFLICT (idempotency_key) DO UPDATE SET idempotency_key = EXCLUDED.idempotency_key
                                WHERE email_outbox.notification_type = EXCLUDED.notification_type
                                    AND email_outbox.recipient_email = EXCLUDED.recipient_email
                RETURNING id
                """)
                .param("type", command.type().name())
                                .param("recipient", recipient)
                .param("variables", json(command))
                .param("idempotencyKey", command.idempotencyKey())
                .param("customerId", command.customerId())
                .param("correlationId", command.correlationId())
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(now.plus(command.type().timeToLive())))
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                    "Email idempotency key is already assigned to a different notification."));
            return Optional.of(outboxId);
    }

    private void validate(EmailNotificationCommand command) {
        if (command == null || command.type() == null || command.recipient() == null
                || !EMAIL.matcher(command.recipient().trim()).matches()
                || HEADER_BREAK.matcher(command.recipient()).find()) {
            throw new IllegalArgumentException("A valid notification type and recipient are required.");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank() || command.idempotencyKey().length() > 160) {
            throw new IllegalArgumentException("A bounded idempotency key is required.");
        }
        Set<String> missing = new HashSet<>(command.type().requiredVariables());
        missing.removeAll(command.variables().keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing template variables: " + missing);
        }
        if (!command.type().requiredVariables().containsAll(command.variables().keySet())) {
            throw new IllegalArgumentException("Unexpected template variables for " + command.type());
        }
        command.variables().forEach((key, value) -> {
            if (value == null || HEADER_BREAK.matcher(value).find() && key.toLowerCase(Locale.ROOT).contains("url")) {
                throw new IllegalArgumentException("Invalid template variable: " + key);
            }
            if (key.toLowerCase(Locale.ROOT).endsWith("url") && !(value.startsWith("https://") || value.startsWith("http://localhost"))) {
                throw new IllegalArgumentException("Template URLs must use HTTPS.");
            }
        });
    }

    private String json(EmailNotificationCommand command) {
        try {
            return objectMapper.writeValueAsString(command.variables());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Template variables are not serializable.", exception);
        }
    }
}