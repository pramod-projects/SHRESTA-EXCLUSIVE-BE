package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

public final class CustomerAuthIdentitiesMigration {

    private CustomerAuthIdentitiesMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("customer_auth_identities")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE customer_auth_identities (
                    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                    customer_id      UUID        NOT NULL REFERENCES customer_accounts(id) ON DELETE CASCADE,
                    identity_type    VARCHAR(24) NOT NULL,
                    identity_value   VARCHAR(254) NOT NULL UNIQUE,
                    is_verified      BOOLEAN     NOT NULL DEFAULT FALSE,
                    last_verified_at TIMESTAMPTZ,
                    metadata         JSONB       NOT NULL DEFAULT '{}'::jsonb,
                    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT chk_customer_identity_type        CHECK (identity_type IN ('EMAIL','MOBILE')),
                    CONSTRAINT chk_customer_identity_value_lower CHECK (identity_value = lower(identity_value))
                )
                """,
                "CREATE INDEX idx_customer_auth_customer ON customer_auth_identities (customer_id, identity_type)"
            ))
            .build();
    }
}
