package com.shrestaexclusive.platform.email.configuration;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.shrestaexclusive.platform.email.domain.EmailProvider;

@Component("email")
public class EmailHealthIndicator implements HealthIndicator {
    private final EmailProperties properties;
    private final JdbcClient jdbcClient;
    private final List<EmailProvider> providers;

    public EmailHealthIndicator(EmailProperties properties, JdbcClient jdbcClient, List<EmailProvider> providers) {
        this.properties = properties;
        this.jdbcClient = jdbcClient;
        this.providers = providers;
    }

    @Override
    public Health health() {
        if (!properties.isEnabled()) {
            return Health.up().withDetail("enabled", false).build();
        }
        boolean dev = "DEV".equals(properties.getEnvironment().trim().toUpperCase(Locale.ROOT));
        List<String> configuredProviders = providers.stream().filter(EmailProvider::configured)
            .filter(provider -> dev == "CAPTURE".equals(provider.code()))
            .filter(provider -> dev || properties.getProviderOrder().contains(provider.code()))
                .map(EmailProvider::code).sorted().toList();
        if (configuredProviders.isEmpty()) {
            return Health.down().withDetail("enabled", true).withDetail("configuredProviders", List.of()).build();
        }
        try {
            QueueHealth queue = jdbcClient.sql("""
                    SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)::bigint AS oldest_age_seconds,
                           count(*) AS actionable_count,
                           count(*) FILTER (WHERE state = 'OUTCOME_UNKNOWN') AS unknown_count
                    FROM email_outbox
                    WHERE state IN ('PENDING','PROCESSING','RETRY_WAIT','OUTCOME_UNKNOWN')
                    """).query((rs, rowNum) -> new QueueHealth(rs.getLong("oldest_age_seconds"),
                            rs.getLong("actionable_count"), rs.getLong("unknown_count"))).single();
            Health.Builder health = queue.oldestAgeSeconds() > properties.getUnknownOutcomeAlertAge().toSeconds()
                    ? Health.status("DEGRADED") : Health.up();
            return health.withDetail("enabled", true)
                    .withDetail("configuredProviders", configuredProviders)
                    .withDetail("actionableCount", queue.actionableCount())
                    .withDetail("oldestActionableAgeSeconds", queue.oldestAgeSeconds())
                    .withDetail("unknownOutcomeCount", queue.unknownCount())
                    .build();
        } catch (RuntimeException exception) {
            return Health.down(exception).withDetail("enabled", true)
                    .withDetail("configuredProviders", configuredProviders).build();
        }
    }

    private record QueueHealth(long oldestAgeSeconds, long actionableCount, long unknownCount) {}
}