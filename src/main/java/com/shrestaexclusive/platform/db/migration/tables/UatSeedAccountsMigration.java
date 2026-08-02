package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

/**
 * Schema migration for uat_seed_accounts.
 *
 * This table is a UAT/dev-only control table used by CustomerAuthService
 * to authenticate test users with a static OTP (no real OTP delivery needed).
 * It must exist in all non-production environments (local, dev, uat).
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
