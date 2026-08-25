package com.shrestaexclusive.platform.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundPolicyConfigurationService {
    public static final String CUSTOMER_REFUND_POLICY = "CUSTOMER_REFUND";

    private final JdbcClient jdbcClient;

    public RefundPolicyConfigurationService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public RefundPolicyConfigurationView current() {
        return jdbcClient.sql("""
                SELECT policy_key, eligibility_days, version, updated_by, update_reason, updated_at
                FROM refund_policy_configuration
                WHERE policy_key = :policyKey
                """).param("policyKey", CUSTOMER_REFUND_POLICY).query(this::map).single();
    }

    @Transactional
    public RefundPolicyConfigurationView update(int eligibilityDays, String actor, String reason) {
        if (eligibilityDays < 0 || eligibilityDays > 365) {
            throw new IllegalArgumentException("Refund eligibility days must be between 0 and 365.");
        }
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Actor and reason are required for refund policy changes.");
        }

        int previousDays = jdbcClient.sql("""
                SELECT eligibility_days
                FROM refund_policy_configuration
                WHERE policy_key = :policyKey
                FOR UPDATE
                """).param("policyKey", CUSTOMER_REFUND_POLICY).query(Integer.class).single();
        Instant now = Instant.now();
        RefundPolicyConfigurationView updated = jdbcClient.sql("""
                UPDATE refund_policy_configuration
                SET eligibility_days = :eligibilityDays,
                    version = version + 1,
                    updated_by = :actor,
                    update_reason = :reason,
                    updated_at = :now
                WHERE policy_key = :policyKey
                RETURNING policy_key, eligibility_days, version, updated_by, update_reason, updated_at
                """).param("eligibilityDays", eligibilityDays)
                .param("actor", actor.trim())
                .param("reason", reason.trim())
                .param("now", Timestamp.from(now))
                .param("policyKey", CUSTOMER_REFUND_POLICY)
                .query(this::map).single();
        jdbcClient.sql("""
                INSERT INTO refund_policy_configuration_audit (
                    policy_key, previous_eligibility_days, new_eligibility_days,
                    changed_by, change_reason, changed_at
                ) VALUES (:policyKey, :previousDays, :newDays, :actor, :reason, :now)
                """).param("policyKey", CUSTOMER_REFUND_POLICY)
                .param("previousDays", previousDays)
                .param("newDays", eligibilityDays)
                .param("actor", actor.trim())
                .param("reason", reason.trim())
                .param("now", Timestamp.from(now)).update();
        return updated;
    }

    private RefundPolicyConfigurationView map(ResultSet rs, @SuppressWarnings("unused") int rowNum) throws SQLException {
        return new RefundPolicyConfigurationView(
                rs.getString("policy_key"),
                rs.getInt("eligibility_days"),
                rs.getLong("version"),
                rs.getString("updated_by"),
                rs.getString("update_reason"),
                rs.getTimestamp("updated_at").toInstant());
    }

    public record RefundPolicyConfigurationView(
            String policyKey,
            int eligibilityDays,
            long version,
            String updatedBy,
            String updateReason,
            Instant updatedAt
    ) {}
}