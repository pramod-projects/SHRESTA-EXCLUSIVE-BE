package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

public final class CustomerSmsMessagesMigration {

    private CustomerSmsMessagesMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("customer_sms_messages")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE customer_sms_messages (
                    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                    customer_id        UUID         NOT NULL REFERENCES customer_accounts(id) ON DELETE CASCADE,
                    mobile_number      VARCHAR(16)  NOT NULL,
                    purpose            VARCHAR(40)  NOT NULL,
                    message_body       TEXT         NOT NULL,
                    status             VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
                    provider_used      VARCHAR(24),
                    provider_priority  VARCHAR(80)  NOT NULL DEFAULT 'SPRINGEDGE,MSG91',
                    sent_at            TIMESTAMPTZ,
                    failure_reason     VARCHAR(255),
                    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    CONSTRAINT chk_customer_sms_status CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED')),
                    CONSTRAINT chk_customer_sms_provider CHECK (provider_used IS NULL OR provider_used IN ('SPRINGEDGE','MSG91'))
                )
                """,
                "CREATE INDEX idx_customer_sms_messages_customer ON customer_sms_messages (customer_id, created_at DESC)",
                "CREATE INDEX idx_customer_sms_messages_status ON customer_sms_messages (status, created_at DESC)"
            ))
            .build();
    }
}
