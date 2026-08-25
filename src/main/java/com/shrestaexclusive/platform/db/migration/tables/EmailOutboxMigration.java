package com.shrestaexclusive.platform.db.migration.tables;

import java.util.List;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;

public final class EmailOutboxMigration {
    private EmailOutboxMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("email_outbox")
                .transition(List.of(-1), 0, List.of("""
                    CREATE TABLE email_outbox (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        notification_type VARCHAR(48) NOT NULL REFERENCES notification_configuration(notification_type),
                        recipient_email VARCHAR(320) NOT NULL,
                        template_variables JSONB NOT NULL,
                        template_version INTEGER NOT NULL DEFAULT 1,
                        idempotency_key VARCHAR(160) NOT NULL UNIQUE,
                        customer_id UUID REFERENCES customer_accounts(id) ON DELETE SET NULL,
                        correlation_id VARCHAR(160),
                        priority SMALLINT NOT NULL DEFAULT 50,
                        state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                        attempt_count INTEGER NOT NULL DEFAULT 0,
                        next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        lease_owner VARCHAR(120),
                        lease_expires_at TIMESTAMPTZ,
                        provider_used VARCHAR(32),
                        provider_message_id VARCHAR(255),
                        last_failure_class VARCHAR(48),
                        expires_at TIMESTAMPTZ NOT NULL,
                        accepted_at TIMESTAMPTZ,
                        delivered_at TIMESTAMPTZ,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        CONSTRAINT chk_email_outbox_state CHECK (state IN ('PENDING','PROCESSING','RETRY_WAIT','PROVIDER_ACCEPTED','DELIVERED','OUTCOME_UNKNOWN','FAILED','BOUNCED','REJECTED','COMPLAINT'))
                    )
                    """, "CREATE INDEX idx_email_outbox_claim ON email_outbox (state, next_attempt_at, priority, created_at)",
                    "CREATE INDEX idx_email_outbox_lease ON email_outbox (lease_expires_at) WHERE state = 'PROCESSING'"))
                .transition(List.of(0), 1, List.of(
                    "ALTER TABLE email_outbox ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0"))
                .build();
    }
}