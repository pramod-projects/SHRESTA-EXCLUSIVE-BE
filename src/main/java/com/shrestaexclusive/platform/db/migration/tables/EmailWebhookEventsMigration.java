package com.shrestaexclusive.platform.db.migration.tables;

import java.util.List;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;

public final class EmailWebhookEventsMigration {
    private EmailWebhookEventsMigration() {}
    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("email_webhook_events")
                .transition(List.of(-1), 0, List.of("""
                    CREATE TABLE email_webhook_events (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        provider VARCHAR(32) NOT NULL,
                        provider_event_id VARCHAR(160) NOT NULL,
                        provider_message_id VARCHAR(255),
                        event_type VARCHAR(32) NOT NULL,
                        payload_digest VARCHAR(64) NOT NULL,
                        occurred_at TIMESTAMPTZ NOT NULL,
                        processing_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        processed_at TIMESTAMPTZ,
                        UNIQUE (provider, provider_event_id),
                        CONSTRAINT chk_email_webhook_event_type CHECK (event_type IN ('ACCEPTED','DELIVERED','DEFERRED','BOUNCED','REJECTED','COMPLAINT'))
                    )
                    """, "CREATE INDEX idx_email_webhook_message ON email_webhook_events (provider, provider_message_id)"))
                .build();
    }
}