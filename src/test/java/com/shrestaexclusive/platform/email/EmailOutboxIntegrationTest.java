package com.shrestaexclusive.platform.email;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.db.migration.framework.MigrationRunner;
import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import com.shrestaexclusive.platform.db.migration.tables.NotificationConfigurationMigration;
import com.shrestaexclusive.platform.email.application.EmailNotificationCommand;
import com.shrestaexclusive.platform.email.application.JdbcEmailNotificationService;
import com.shrestaexclusive.platform.email.configuration.EmailHealthIndicator;
import com.shrestaexclusive.platform.email.configuration.EmailProperties;
import com.shrestaexclusive.platform.email.configuration.NotificationConfigurationService;
import com.shrestaexclusive.platform.email.domain.EmailMessage;
import com.shrestaexclusive.platform.email.domain.EmailProvider;
import com.shrestaexclusive.platform.email.domain.EmailProviderResult;
import com.shrestaexclusive.platform.email.domain.NotificationType;
import com.shrestaexclusive.platform.email.domain.ProviderAttempt;
import com.shrestaexclusive.platform.email.domain.ProviderOutcome;
import com.shrestaexclusive.platform.email.webhook.EmailWebhookService;
import com.shrestaexclusive.platform.email.webhook.EmailWebhookService.NormalizedEmailEvent;
import com.shrestaexclusive.platform.email.worker.EmailOutboxRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Testcontainers
class EmailOutboxIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("shresta").withUsername("shresta").withPassword("shresta");
    static DriverManagerDataSource dataSource; static JdbcClient jdbc; static JdbcEmailNotificationService service;
    static EmailWebhookService webhookService;

    @BeforeAll
    @SuppressWarnings("unused")
    static void setup() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource); service = new JdbcEmailNotificationService(jdbc, new ObjectMapper());
        webhookService = new EmailWebhookService(jdbc, new SimpleMeterRegistry());
    }

    @Test void enqueueRollsBackWithOwningTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> { service.enqueue(command("rollback-" + id)); status.setRollbackOnly(); });
        assertThat(jdbc.sql("SELECT count(*) FROM email_outbox WHERE idempotency_key = :key").param("key", "rollback-" + id).query(Long.class).single()).isZero();
    }

    @Test void disabledOptionalNotificationIsSuppressedWithoutFailure() {
        String key = "suppressed-" + UUID.randomUUID();
        jdbc.sql("UPDATE notification_configuration SET enabled = false WHERE notification_type = 'ORDER_SHIPPED'").update();
        try {
            var result = service.enqueue(new EmailNotificationCommand(NotificationType.ORDER_SHIPPED,
                    "customer@example.com", Map.of("customerName", "Customer", "orderNumber", "S-1",
                            "trackingUrl", "https://example.test/track/S-1"), key, null, "test"));

            assertThat(result).isEmpty();
            assertThat(jdbc.sql("SELECT count(*) FROM email_outbox WHERE idempotency_key = :key")
                    .param("key", key).query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("UPDATE notification_configuration SET enabled = true WHERE notification_type = 'ORDER_SHIPPED'").update();
        }
    }

        @Test void notificationConfigurationChangeRecordsImmutableAuditHistory() {
        EmailProperties properties = new EmailProperties();
        properties.setEnvironment("PROD");
        NotificationConfigurationService configurationService = new NotificationConfigurationService(jdbc, properties);
        String reason = "Pause shipment notices " + UUID.randomUUID();

        configurationService.update(NotificationType.ORDER_SHIPPED, false, "reviewer@shresta.local", reason);

        Map<String, Object> audit = jdbc.sql("""
            SELECT previous_enabled, new_enabled, changed_by, change_reason
            FROM notification_configuration_audit
            WHERE notification_type = 'ORDER_SHIPPED' AND change_reason = :reason
            """).param("reason", reason).query().singleRow();
        assertThat(audit).containsEntry("previous_enabled", true)
            .containsEntry("new_enabled", false)
            .containsEntry("changed_by", "reviewer@shresta.local")
            .containsEntry("change_reason", reason);

        configurationService.update(NotificationType.ORDER_SHIPPED, true, "reviewer@shresta.local", "Restore shipment notices");
        }

    @Test void notificationConfigurationVersionZeroUpgradesToAuditSchema() throws Exception {
        String schema = "notification_audit_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            JdbcClient isolatedJdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            var initialTransition = NotificationConfigurationMigration.transitionPlan().transitions().getFirst();
            TransitionPlan versionZeroPlan = TransitionPlan.forTable("notification_configuration")
                    .transition(initialTransition.fromVersions(), initialTransition.toVersion(), initialTransition.sql())
                    .build();

            MigrationRunner.run(connection, List.of(versionZeroPlan));
            assertThat(isolatedJdbc.sql("SELECT version FROM shresta_table_migration_versions WHERE table_name = 'notification_configuration'")
                    .query(Integer.class).single()).isZero();

            MigrationRunner.run(connection, List.of(NotificationConfigurationMigration.transitionPlan()));
            assertThat(isolatedJdbc.sql("SELECT version FROM shresta_table_migration_versions WHERE table_name = 'notification_configuration'")
                    .query(Integer.class).single()).isEqualTo(1);
            assertThat(isolatedJdbc.sql("SELECT to_regclass('notification_configuration_audit')")
                    .query(String.class).single()).isEqualTo("notification_configuration_audit");
        } finally {
            jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        }
    }

    @Test void idempotentEnqueueReturnsOneRowAndWorkerClaimsIt() {
        String key = "claim-" + UUID.randomUUID(); UUID first = service.enqueue(command(key)).orElseThrow(); UUID second = service.enqueue(command(key)).orElseThrow();
        assertThat(second).isEqualTo(first);
        var claimed = new EmailOutboxRepository(jdbc, new ObjectMapper()).claim("test-worker", 10, Duration.ofMinutes(1));
        assertThat(claimed).extracting(item -> item.id()).contains(first);
    }

        @Test void deliveryAttemptRecordsTheExactProviderIdempotencyKey() {
        String key = "provider-key-" + UUID.randomUUID();
        UUID id = service.enqueue(command(key)).orElseThrow();
        EmailOutboxRepository repository = new EmailOutboxRepository(jdbc, new ObjectMapper());
        var claimed = repository.claim("provider-key-worker", 100, Duration.ofMinutes(1)).stream()
            .filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        ProviderAttempt accepted = new ProviderAttempt("RESEND",
            new EmailProviderResult(ProviderOutcome.ACCEPTED, "provider-id", 200, null, 1));

        repository.complete(claimed, List.of(accepted), null);

        assertThat(jdbc.sql("SELECT provider_idempotency_key FROM email_delivery_attempts WHERE outbox_id = :id")
            .param("id", id).query(String.class).single()).isEqualTo(key);
        }

        @Test void idempotencyKeyCannotBeReusedForDifferentRecipient() {
        String key = "collision-" + UUID.randomUUID();
        service.enqueue(command(key));
        EmailNotificationCommand conflicting = new EmailNotificationCommand(NotificationType.OTP,
            "different@example.com", Map.of("otp", "123456", "expiresMinutes", "10"), key, null, "test");

        assertThatThrownBy(() -> service.enqueue(conflicting))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("idempotency key");
        assertThat(jdbc.sql("SELECT count(*) FROM email_outbox WHERE idempotency_key = :key")
            .param("key", key).query(Long.class).single()).isEqualTo(1);
        }

        @Test void maintenanceTerminatesExpiredActionableNotification() {
            UUID id = service.enqueue(command("expired-" + UUID.randomUUID())).orElseThrow();
        jdbc.sql("UPDATE email_outbox SET expires_at = :expired WHERE id = :id")
            .param("expired", java.sql.Timestamp.from(Instant.now().minusSeconds(1)))
            .param("id", id).update();
        EmailOutboxRepository repository = new EmailOutboxRepository(jdbc, new ObjectMapper());

        var result = repository.maintain(Duration.ofDays(30), Duration.ofDays(90), Duration.ofMinutes(15));

        assertThat(result.expiredOutboxRows()).isEqualTo(1);
        assertThat(outboxState(id)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT last_failure_class FROM email_outbox WHERE id = :id")
            .param("id", id).query(String.class).single()).isEqualTo("NOTIFICATION_EXPIRED");
        }

        @Test void staleLeaseOwnerCannotCompleteReclaimedEmail() {
            UUID id = service.enqueue(command("lease-" + UUID.randomUUID())).orElseThrow();
        EmailOutboxRepository repository = new EmailOutboxRepository(jdbc, new ObjectMapper());
        var staleClaim = repository.claim("worker-one", 100, Duration.ofMinutes(1)).stream()
            .filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        jdbc.sql("UPDATE email_outbox SET lease_expires_at = :expired WHERE id = :id")
            .param("expired", java.sql.Timestamp.from(Instant.now().minusSeconds(1))).param("id", id).update();
        var currentClaim = repository.claim("worker-two", 100, Duration.ofMinutes(1)).stream()
            .filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        ProviderAttempt accepted = new ProviderAttempt("BREVO",
            new EmailProviderResult(ProviderOutcome.ACCEPTED, "provider-id", 201, null, 1));

        assertThatThrownBy(() -> repository.complete(staleClaim, List.of(accepted), null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM email_delivery_attempts WHERE outbox_id = :id")
            .param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT lease_owner FROM email_outbox WHERE id = :id")
            .param("id", id).query(String.class).single()).isEqualTo(currentClaim.leaseOwner());
        }

        @Test void duplicateAndLateWebhookEventsCannotRegressTerminalState() {
        String messageId = "webhook-" + UUID.randomUUID();
        UUID outboxId = providerAcceptedOutbox(messageId);
        Instant occurredAt = Instant.now();

        assertThat(webhookService.accept("BREVO", "delivered-" + messageId, messageId,
            NormalizedEmailEvent.DELIVERED, occurredAt, "{\"event\":\"delivered\"}")).isTrue();
        assertThat(webhookService.accept("BREVO", "delivered-" + messageId, messageId,
            NormalizedEmailEvent.DELIVERED, occurredAt, "{\"event\":\"delivered\"}")).isFalse();
        assertThat(webhookService.accept("BREVO", "late-bounce-" + messageId, messageId,
            NormalizedEmailEvent.BOUNCED, occurredAt.minusSeconds(30), "{\"event\":\"bounce\"}")).isTrue();

        assertThat(outboxState(outboxId)).isEqualTo("DELIVERED");
        assertThat(jdbc.sql("SELECT count(*) FROM email_webhook_events WHERE provider_message_id = :messageId")
            .param("messageId", messageId).query(Long.class).single()).isEqualTo(2);
        }

        @Test void complaintEscalatesAnExistingTerminalState() {
        String messageId = "complaint-" + UUID.randomUUID();
        UUID outboxId = providerAcceptedOutbox(messageId);

        webhookService.accept("BREVO", "delivered-" + messageId, messageId,
            NormalizedEmailEvent.DELIVERED, Instant.now(), "{\"event\":\"delivered\"}");
        webhookService.accept("BREVO", "complaint-" + messageId, messageId,
            NormalizedEmailEvent.COMPLAINT, Instant.now(), "{\"event\":\"complaint\"}");

        assertThat(outboxState(outboxId)).isEqualTo("COMPLAINT");
        }

    @Test void webhookArrivingBeforeProviderCorrelationIsReconciledLater() {
        String messageId = "early-webhook-" + UUID.randomUUID();
        UUID outboxId = service.enqueue(command("early-webhook-key-" + UUID.randomUUID())).orElseThrow();

        assertThat(webhookService.accept("BREVO", "early-" + messageId, messageId,
                NormalizedEmailEvent.DELIVERED, Instant.now(), "{\"event\":\"delivered\"}")).isTrue();
        assertThat(jdbc.sql("SELECT processing_state FROM email_webhook_events WHERE provider_event_id = :eventId")
                .param("eventId", "early-" + messageId).query(String.class).single()).isEqualTo("PENDING");

        jdbc.sql("""
            UPDATE email_outbox SET state = 'PROVIDER_ACCEPTED', provider_used = 'BREVO',
                provider_message_id = :messageId WHERE id = :id
            """).param("messageId", messageId).param("id", outboxId).update();
        webhookService.reconcilePending();

        assertThat(outboxState(outboxId)).isEqualTo("DELIVERED");
        assertThat(jdbc.sql("SELECT processing_state FROM email_webhook_events WHERE provider_event_id = :eventId")
                .param("eventId", "early-" + messageId).query(String.class).single()).isEqualTo("PROCESSED");
    }

    @Test void emailHealthReportsConfiguredProviderAndQueueStateWithoutSecrets() {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(true);
        properties.setEnvironment("PROD");
        properties.setProviderOrder(List.of("BREVO"));
        EmailProvider provider = new EmailProvider() {
            @Override public String code() { return "BREVO"; }
            @Override public boolean enabled() { return true; }
            @Override public boolean configured() { return true; }
            @Override public boolean webhookConfigured() { return true; }
            @Override public EmailProviderResult send(EmailMessage message) { throw new UnsupportedOperationException(); }
        };

        var health = new EmailHealthIndicator(properties, jdbc, List.of(provider)).health();

        assertThat(health.getStatus().getCode()).isIn("UP", "DEGRADED");
        assertThat(health.getDetails()).containsEntry("configuredProviders", List.of("BREVO"))
                .containsKeys("actionableCount", "oldestActionableAgeSeconds", "unknownOutcomeCount");
        assertThat(health.getDetails().toString()).doesNotContain("apiKey", "secret");
    }

        private static UUID providerAcceptedOutbox(String messageId) {
        UUID id = service.enqueue(command("webhook-key-" + UUID.randomUUID())).orElseThrow();
        jdbc.sql("""
            UPDATE email_outbox SET state = 'PROVIDER_ACCEPTED', provider_used = 'BREVO',
                provider_message_id = :messageId WHERE id = :id
            """).param("messageId", messageId).param("id", id).update();
        return id;
        }

        private static String outboxState(UUID id) {
        return jdbc.sql("SELECT state FROM email_outbox WHERE id = :id")
            .param("id", id).query(String.class).single();
        }

    private static EmailNotificationCommand command(String key) {
        return new EmailNotificationCommand(NotificationType.OTP, "customer@example.com", Map.of("otp", "123456", "expiresMinutes", "10"), key, null, "test");
    }
}