package com.shrestaexclusive.platform.email;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shrestaexclusive.platform.email.application.EmailNotificationCommand;
import com.shrestaexclusive.platform.email.application.EmailNotificationService;
import com.shrestaexclusive.platform.email.domain.NotificationType;
import com.shrestaexclusive.platform.email.worker.EmailOutboxWorker;

@Tag("live-email")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LiveEmailProviderVerificationTest {
    private static final String CONFIRMATION = "SEND_EXACTLY_ONE_PER_PROVIDER";
    private static final Set<String> PROVIDERS = Set.of("BREVO", "RESEND", "MAILJET", "MAILERSEND", "ELASTIC_EMAIL");
    private static final String PROVIDER = environment("SHRESTA_EMAIL_LIVE_PROVIDER", "NONE").toUpperCase();
    private static final String RECIPIENT = environment("SHRESTA_EMAIL_LIVE_RECIPIENT", "invalid@example.test");
    private static final NotificationType NOTIFICATION_TYPE = notificationType();

    @Container
    @ServiceConnection
    @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shresta")
            .withUsername("shresta_app")
            .withPassword("change-me");

    @DynamicPropertySource
    @SuppressWarnings("unused")
    static void liveProviderProperties(DynamicPropertyRegistry registry) {
        registry.add("shresta.environment.mode", () -> "UAT");
        registry.add("shresta.email.enabled", () -> "true");
        registry.add("shresta.email.environment", () -> "UAT");
        registry.add("shresta.email.provider-order", () -> PROVIDER);
        registry.add("shresta.email.uat-allowlist", () -> RECIPIENT);
        registry.add("shresta.email.uat-recipient-override", () -> RECIPIENT);
        registry.add("shresta.email.batch-size", () -> "1");
        registry.add("shresta.email.max-attempts", () -> "1");
        registry.add("shresta.email.providers.brevo.enabled", () -> enabled("BREVO"));
        registry.add("shresta.email.providers.resend.enabled", () -> enabled("RESEND"));
        registry.add("shresta.email.providers.mailjet.enabled", () -> enabled("MAILJET"));
        registry.add("shresta.email.providers.mailer-send.enabled", () -> enabled("MAILERSEND"));
        registry.add("shresta.email.providers.elastic-email.enabled", () -> enabled("ELASTIC_EMAIL"));
    }

    @Autowired
    private EmailNotificationService notificationService;

    @Autowired
    private EmailOutboxWorker worker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void sendsExactlyOneControlledNotificationThroughSelectedProvider() {
        assertThat(System.getenv("SHRESTA_EMAIL_LIVE_CONFIRM")).isEqualTo(CONFIRMATION);
        assertThat(PROVIDERS).contains(PROVIDER);
        assertThat(RECIPIENT).contains("@").doesNotEndWith(".test");

        UUID outboxId = notificationService.enqueue(new EmailNotificationCommand(
            NOTIFICATION_TYPE,
                RECIPIENT,
            variables(),
            "live-provider-verification:" + NOTIFICATION_TYPE + ":" + PROVIDER + ":" + UUID.randomUUID(),
                null,
            "live-provider-verification:" + NOTIFICATION_TYPE + ":" + PROVIDER
        )).orElseThrow();

        worker.poll();

        Map<String, Object> outbox = jdbcTemplate.queryForMap("""
                SELECT state, provider_used, provider_message_id, attempt_count
                FROM email_outbox
                WHERE id = ?
                """, outboxId);
        assertThat(outbox.get("state")).isEqualTo("PROVIDER_ACCEPTED");
        assertThat(outbox.get("provider_used")).isEqualTo(PROVIDER);
        assertThat(String.valueOf(outbox.get("provider_message_id"))).isNotBlank().isNotEqualTo("null");
        assertThat(outbox.get("attempt_count")).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM email_delivery_attempts
                WHERE outbox_id = ?
                  AND provider = ?
                  AND outcome = 'ACCEPTED'
                  AND provider_message_id IS NOT NULL
                """, Integer.class, outboxId, PROVIDER)).isEqualTo(1);
    }

    private static String enabled(String expectedProvider) {
        return Boolean.toString(expectedProvider.equals(PROVIDER));
    }

    private static NotificationType notificationType() {
        NotificationType type = NotificationType.valueOf(
                environment("SHRESTA_EMAIL_LIVE_NOTIFICATION_TYPE", "ACCOUNT_SECURITY").toUpperCase());
        if (type != NotificationType.ACCOUNT_SECURITY && type != NotificationType.OTP) {
            throw new IllegalArgumentException("Live verification supports only ACCOUNT_SECURITY or OTP");
        }
        return type;
    }

    private static Map<String, String> variables() {
        return switch (NOTIFICATION_TYPE) {
            case ACCOUNT_SECURITY -> Map.of("message", "Controlled SHRESTA provider verification for " + PROVIDER + ".");
            case OTP -> Map.of(
                    "otp", "%06d".formatted(new SecureRandom().nextInt(1_000_000)),
                    "expiresMinutes", "10"
            );
            default -> throw new IllegalStateException("Unsupported live notification type " + NOTIFICATION_TYPE);
        };
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}