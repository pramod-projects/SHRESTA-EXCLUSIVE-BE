package com.shrestaexclusive.platform.db.migration.tables;

import java.util.List;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;

public final class RefundPolicyConfigurationMigration {
    private RefundPolicyConfigurationMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("refund_policy_configuration")
                .transition(List.of(-1), 0, List.of("""
                    CREATE TABLE refund_policy_configuration (
                        policy_key VARCHAR(48) PRIMARY KEY,
                        eligibility_days INTEGER NOT NULL CHECK (eligibility_days BETWEEN 0 AND 365),
                        version BIGINT NOT NULL DEFAULT 0,
                        updated_by VARCHAR(160) NOT NULL DEFAULT 'SYSTEM',
                        update_reason VARCHAR(500),
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """, """
                    INSERT INTO refund_policy_configuration (policy_key, eligibility_days)
                    VALUES ('CUSTOMER_REFUND', 3)
                    """, """
                    CREATE TABLE refund_policy_configuration_audit (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        policy_key VARCHAR(48) NOT NULL REFERENCES refund_policy_configuration(policy_key),
                        previous_eligibility_days INTEGER NOT NULL,
                        new_eligibility_days INTEGER NOT NULL,
                        changed_by VARCHAR(160) NOT NULL,
                        change_reason VARCHAR(500) NOT NULL,
                        changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """, """
                    CREATE INDEX idx_refund_policy_configuration_audit_key_time
                    ON refund_policy_configuration_audit (policy_key, changed_at DESC)
                    """))
                .build();
    }
}