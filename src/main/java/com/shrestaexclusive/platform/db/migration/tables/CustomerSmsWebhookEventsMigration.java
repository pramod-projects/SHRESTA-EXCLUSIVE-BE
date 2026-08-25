package com.shrestaexclusive.platform.db.migration.tables;

import java.util.List;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;

public final class CustomerSmsWebhookEventsMigration {

    private CustomerSmsWebhookEventsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("customer_sms_webhook_events")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE customer_sms_webhook_events (
                    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                    provider               VARCHAR(24)  NOT NULL,
                    provider_event_id      VARCHAR(120),
                    provider_message_id    VARCHAR(160),
                    event_type             VARCHAR(80),
                    delivery_status        VARCHAR(40),
                    mobile_number          VARCHAR(16),
                    linked_sms_message_id  UUID         REFERENCES customer_sms_messages(id) ON DELETE SET NULL,
                    payload_json           JSONB        NOT NULL,
                    processing_status      VARCHAR(24)  NOT NULL DEFAULT 'RECEIVED',
                    failure_reason         VARCHAR(255),
                    processed_at           TIMESTAMPTZ,
                    received_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    CONSTRAINT chk_customer_sms_webhook_provider
                        CHECK (provider IN ('SPRINGEDGE','MSG91')),
                    CONSTRAINT chk_customer_sms_webhook_processing_status
                        CHECK (processing_status IN ('RECEIVED','LINKED','IGNORED','FAILED'))
                )
                """,
                "CREATE INDEX idx_customer_sms_webhook_provider_message ON customer_sms_webhook_events (provider, provider_message_id, received_at DESC)",
                "CREATE INDEX idx_customer_sms_webhook_received ON customer_sms_webhook_events (received_at DESC)",
                "CREATE INDEX idx_customer_sms_webhook_linked_message ON customer_sms_webhook_events (linked_sms_message_id, received_at DESC)"
            ))
            .build();
    }
}
