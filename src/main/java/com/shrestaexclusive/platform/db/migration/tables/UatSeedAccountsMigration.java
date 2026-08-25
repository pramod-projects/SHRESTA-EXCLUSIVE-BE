package com.shrestaexclusive.platform.db.migration.tables;

import java.util.List;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;

/**
 * Schema migration for uat_seed_accounts.
 *
 * This table can hold local/dev/UAT static-OTP control rows used by
 * CustomerAuthService. Its schema exists in every environment, but default
 * account rows are inserted only by the local/dev full seeder.
 *
 * The service query joins:
 *   uat_seed_accounts → customer_accounts (via identity_email = primary_email)
 *   customer_accounts → customer_auth_identities (via id = customer_id)
 */
public final class UatSeedAccountsMigration {

    private UatSeedAccountsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("uat_seed_accounts")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE uat_seed_accounts (
                    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                    identity_email   VARCHAR(254) NOT NULL UNIQUE,
                    otp_code         VARCHAR(10)  NOT NULL,
                    customer_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
                    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
                    description      VARCHAR(255),
                    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    CONSTRAINT chk_uat_seed_email_lower CHECK (identity_email = lower(identity_email))
                )
                """
            ))
            .build();
    }
}
