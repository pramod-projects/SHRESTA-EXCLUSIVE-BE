package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

public final class AdminChangeRequestsMigration {

    private AdminChangeRequestsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("admin_change_requests")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE admin_change_requests (
                    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                    request_key       VARCHAR(120) NOT NULL UNIQUE,
                    request_type      VARCHAR(80) NOT NULL,
                    entity_type       VARCHAR(80) NOT NULL,
                    entity_key        VARCHAR(180) NOT NULL,
                    action            VARCHAR(32) NOT NULL,
                    status            VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
                    submitted_by_role VARCHAR(80) NOT NULL,
                    submitted_by      VARCHAR(160),
                    reviewed_by_role  VARCHAR(80),
                    reviewed_by       VARCHAR(160),
                    review_note       TEXT,
                    payload           JSONB       NOT NULL DEFAULT '{}'::jsonb,
                    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                    reviewed_at       TIMESTAMPTZ,
                    CONSTRAINT chk_admin_change_request_action     CHECK (action IN ('CREATE','UPDATE','ARCHIVE','DELETE')),
                    CONSTRAINT chk_admin_change_request_status     CHECK (status IN ('PENDING_REVIEW','APPROVED','REJECTED','APPLIED','FAILED')),
                    CONSTRAINT chk_admin_change_request_key_format CHECK (request_key ~ '^[a-z][a-z0-9_-]*$')
                )
                """,
                "CREATE INDEX idx_admin_change_requests_status_created ON admin_change_requests (status, created_at DESC)",
                "CREATE INDEX idx_admin_change_requests_entity         ON admin_change_requests (entity_type, entity_key, status)"
            ))
            .build();
    }
}
