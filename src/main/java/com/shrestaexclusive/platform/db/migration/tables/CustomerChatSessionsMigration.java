package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

public final class CustomerChatSessionsMigration {

    private CustomerChatSessionsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("customer_chat_sessions")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE customer_chat_sessions (
                    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                    customer_id  UUID        REFERENCES customer_accounts(id) ON DELETE SET NULL,
                    channel      VARCHAR(32) NOT NULL DEFAULT 'WEB',
                    status       VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                    context_path VARCHAR(320),
                    metadata     JSONB       NOT NULL DEFAULT '{}'::jsonb,
                    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT chk_customer_chat_channel CHECK (channel IN ('WEB','ADMIN','WHATSAPP')),
                    CONSTRAINT chk_customer_chat_status  CHECK (status IN ('ACTIVE','ESCALATED','CLOSED'))
                )
                """,
                "CREATE INDEX idx_customer_chat_sessions_status ON customer_chat_sessions (status, updated_at DESC)"
            ))
            .build();
    }
}
