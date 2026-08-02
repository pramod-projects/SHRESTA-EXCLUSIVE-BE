CREATE TABLE category_family_config (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    family_key   VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    description  TEXT,
    is_active    BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order   INTEGER     NOT NULL DEFAULT 0,
    metadata     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_category_family_key_format CHECK (family_key ~ '^[a-z][a-z0-9_]*$')
);

CREATE INDEX idx_category_family_active_sort ON category_family_config (is_active, sort_order);
