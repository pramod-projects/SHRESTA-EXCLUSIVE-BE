package com.shrestaexclusive.platform.db.migration.tables;

import java.util.List;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;

public final class CustomerAccountsMigration {

    private CustomerAccountsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("customer_accounts")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE customer_accounts (
                    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                    primary_email VARCHAR(254) NOT NULL UNIQUE,
                    display_name  VARCHAR(160) NOT NULL,
                    status        VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                    metadata      JSONB       NOT NULL DEFAULT '{}'::jsonb,
                    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT chk_customer_primary_email_lower CHECK (primary_email = lower(primary_email)),
                    CONSTRAINT chk_customer_account_status      CHECK (status IN ('ACTIVE','SUSPENDED','DELETED'))
                )
                """,
                "CREATE INDEX idx_customer_accounts_status ON customer_accounts (status)"
            ))
            .transition(List.of(0), 1, List.of(
                "ALTER TABLE customer_accounts ALTER COLUMN primary_email DROP NOT NULL"
            ))
            .transition(List.of(1), 2, List.of(
                "ALTER TABLE customer_accounts ADD COLUMN IF NOT EXISTS is_test BOOLEAN NOT NULL DEFAULT FALSE"
            ))
            .build();
    }
}
