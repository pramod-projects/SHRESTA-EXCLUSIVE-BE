package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

public final class CustomerSmsAttemptsMigration {

    private CustomerSmsAttemptsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("customer_sms_attempts")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE customer_sms_attempts (
                    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                    sms_message_id      UUID         NOT NULL REFERENCES customer_sms_messages(id) ON DELETE CASCADE,
                    customer_id         UUID         NOT NULL REFERENCES customer_accounts(id) ON DELETE CASCADE,
                    provider            VARCHAR(24)  NOT NULL,
                    status              VARCHAR(24)  NOT NULL,
                    provider_message_id VARCHAR(120),
                    http_status         INTEGER,
                    request_payload     TEXT,
                    response_payload    TEXT,
                    error_message       VARCHAR(500),
                    attempted_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    CONSTRAINT chk_customer_sms_attempt_provider CHECK (provider IN ('SPRINGEDGE','MSG91')),
                    CONSTRAINT chk_customer_sms_attempt_status CHECK (status IN ('SUCCESS','FAILED'))
                )
                """,
                "CREATE INDEX idx_customer_sms_attempts_message ON customer_sms_attempts (sms_message_id, attempted_at)",
                "CREATE INDEX idx_customer_sms_attempts_customer ON customer_sms_attempts (customer_id, attempted_at DESC)"
            ))
            .build();
    }
}
