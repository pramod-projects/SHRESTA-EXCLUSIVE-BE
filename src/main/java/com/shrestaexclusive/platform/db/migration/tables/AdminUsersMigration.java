package com.shrestaexclusive.platform.db.migration.tables;

import java.util.List;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;

public final class AdminUsersMigration {

    private AdminUsersMigration() {
    }

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("admin_users")
                .transition(List.of(-1), 0, List.of(
                        """
                        CREATE TABLE admin_users (
                            id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                            email            VARCHAR(320) NOT NULL UNIQUE,
                            password_hash    VARCHAR(96)  NOT NULL,
                            role             VARCHAR(40)  NOT NULL,
                            is_active        BOOLEAN      NOT NULL DEFAULT true,
                            created_by_email VARCHAR(320),
                            created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                            updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                            CONSTRAINT chk_admin_users_role CHECK (role IN ('SUPER_ADMIN','CHANGE_SUBMITTER','CHANGE_APPROVER','CHANGE_MANAGER','CHANGE_ADMIN'))
                        )
                        """,
                        "CREATE INDEX idx_admin_users_role_active ON admin_users (role, is_active)"
                ))
                .build();
    }
}
