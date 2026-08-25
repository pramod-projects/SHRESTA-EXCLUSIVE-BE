package com.shrestaexclusive.platform.email.configuration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shrestaexclusive.platform.email.domain.NotificationType;

@Service
public class NotificationConfigurationService {
    private final JdbcClient jdbcClient;
    private final EmailProperties emailProperties;

    public NotificationConfigurationService(JdbcClient jdbcClient, EmailProperties emailProperties) {
        this.jdbcClient = jdbcClient; this.emailProperties = emailProperties;
    }

    public List<NotificationConfigurationView> list() {
        return jdbcClient.sql("SELECT * FROM notification_configuration ORDER BY notification_type")
                .query(this::map).list();
    }

    @Transactional
    public NotificationConfigurationView update(NotificationType type, boolean enabled, String actor, String reason) {
        if (!enabled && type.productionLocked() && "PROD".equals(emailProperties.getEnvironment().trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException(type + " cannot be disabled in production.");
        }
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Actor and reason are required for notification configuration changes.");
        }
        boolean previousEnabled = jdbcClient.sql("""
            SELECT enabled FROM notification_configuration
            WHERE notification_type = :type
            FOR UPDATE
            """).param("type", type.name()).query(Boolean.class).single();
        Instant now = Instant.now();
        NotificationConfigurationView updated = jdbcClient.sql("""
                UPDATE notification_configuration SET enabled = :enabled, version = version + 1,
                    updated_by = :actor, update_reason = :reason, updated_at = :now
                WHERE notification_type = :type RETURNING *
                """).param("enabled", enabled).param("actor", actor).param("reason", reason)
                .param("now", Timestamp.from(now)).param("type", type.name()).query(this::map).single();
            jdbcClient.sql("""
                INSERT INTO notification_configuration_audit
                    (notification_type, previous_enabled, new_enabled, changed_by, change_reason, changed_at)
                VALUES (:type, :previousEnabled, :newEnabled, :actor, :reason, :now)
                """).param("type", type.name()).param("previousEnabled", previousEnabled)
                .param("newEnabled", enabled).param("actor", actor).param("reason", reason)
                .param("now", Timestamp.from(now)).update();
            return updated;
    }

    private NotificationConfigurationView map(ResultSet rs, @SuppressWarnings("unused") int rowNum) throws SQLException {
        return new NotificationConfigurationView(NotificationType.valueOf(rs.getString("notification_type")),
                rs.getBoolean("enabled"), rs.getBoolean("production_locked"), rs.getLong("version"),
                rs.getString("updated_by"), rs.getString("update_reason"), rs.getTimestamp("updated_at").toInstant());
    }

    public record NotificationConfigurationView(NotificationType type, boolean enabled, boolean productionLocked,
            long version, String updatedBy, String updateReason, Instant updatedAt) {}
}