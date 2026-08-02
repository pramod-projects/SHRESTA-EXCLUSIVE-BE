CREATE TABLE storefront_home_sections (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    section_key  VARCHAR(80) NOT NULL UNIQUE,
    section_type VARCHAR(40) NOT NULL,
    eyebrow      VARCHAR(160),
    title        VARCHAR(180) NOT NULL,
    description  TEXT,
    sort_order   INTEGER     NOT NULL DEFAULT 0,
    metadata     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    is_active    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_storefront_section_key_format CHECK (section_key ~ '^[a-z][a-z0-9_]*$')
);

CREATE INDEX idx_storefront_home_sections_active_sort ON storefront_home_sections (is_active, sort_order);
