CREATE TABLE category_product_type_config (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id    UUID        NOT NULL REFERENCES category_family_config(id),
    type_key     VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    is_active    BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order   INTEGER     NOT NULL DEFAULT 0,
    metadata     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_category_product_type        UNIQUE (family_id, type_key),
    CONSTRAINT chk_category_product_type_key_format CHECK (type_key ~ '^[a-z][a-z0-9_]*$')
);

CREATE INDEX idx_category_product_type_family ON category_product_type_config (family_id, is_active, sort_order);
