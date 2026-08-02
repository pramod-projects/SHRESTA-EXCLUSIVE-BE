CREATE TABLE category_styling_config (
    id                        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id                 UUID        NOT NULL REFERENCES category_family_config(id),
    occasion_key              VARCHAR(80) NOT NULL,
    display_name              VARCHAR(120) NOT NULL,
    complementary_family_keys JSONB       NOT NULL DEFAULT '[]'::jsonb,
    rules                     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    is_active                 BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order                INTEGER     NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_category_styling          UNIQUE (family_id, occasion_key),
    CONSTRAINT chk_category_styling_key_format CHECK (occasion_key ~ '^[a-z][a-z0-9_]*$')
);
