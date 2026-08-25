package com.shrestaexclusive.platform.db.migration.tables;

import java.util.List;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;

public final class StorefrontTestAccountsMigration {

    private StorefrontTestAccountsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("storefront_test_accounts")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE storefront_test_accounts (
                    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                    customer_id      UUID        NOT NULL UNIQUE REFERENCES customer_accounts(id) ON DELETE CASCADE,
                    otp_hash         VARCHAR(64) NOT NULL,
                    note             VARCHAR(500),
                    created_by_admin VARCHAR(160) NOT NULL,
                    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
                    otp_revealed_at  TIMESTAMPTZ,
                    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """,
                "CREATE INDEX idx_storefront_test_accounts_active ON storefront_test_accounts (is_active)"
            ))
            .transition(List.of(0), 1, List.of(
                "ALTER TABLE storefront_test_accounts ADD COLUMN IF NOT EXISTS pending_otp VARCHAR(8)"
            ))
            .build();
    }
}
