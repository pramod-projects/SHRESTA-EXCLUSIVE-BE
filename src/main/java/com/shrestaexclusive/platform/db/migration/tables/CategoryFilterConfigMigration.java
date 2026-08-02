package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

public final class CategoryFilterConfigMigration {

    private CategoryFilterConfigMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("category_filter_config")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE category_filter_config (
                    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                    family_id        UUID        NOT NULL REFERENCES category_family_config(id),
                    attribute_id     UUID        NOT NULL REFERENCES category_attribute_config(id),
                    filter_key       VARCHAR(80) NOT NULL,
                    display_name     VARCHAR(120) NOT NULL,
                    frontend_control VARCHAR(32) NOT NULL,
                    backend_mapping  VARCHAR(120) NOT NULL,
                    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
                    sort_order       INTEGER     NOT NULL DEFAULT 0,
                    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT uq_category_filter          UNIQUE (family_id, filter_key),
                    CONSTRAINT chk_category_filter_key_format CHECK (filter_key ~ '^[a-z][a-z0-9_]*$'),
                    CONSTRAINT chk_category_filter_control CHECK (frontend_control IN ('checkbox','radio','range','toggle','swatch'))
                )
                """,
                "CREATE INDEX idx_category_filter_family ON category_filter_config (family_id, is_active, sort_order)"
            ))
            .build();
    }
}
