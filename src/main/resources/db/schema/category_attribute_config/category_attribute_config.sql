-- is_active column added via ALTER in an earlier version — merged here for clean installs.
CREATE TABLE category_attribute_config (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id      UUID        NOT NULL REFERENCES category_family_config(id),
    attribute_key  VARCHAR(80) NOT NULL,
    display_name   VARCHAR(120) NOT NULL,
    data_type      VARCHAR(32) NOT NULL,
    is_required    BOOLEAN     NOT NULL DEFAULT FALSE,
    is_filterable  BOOLEAN     NOT NULL DEFAULT FALSE,
    is_searchable  BOOLEAN     NOT NULL DEFAULT FALSE,
    is_active      BOOLEAN     NOT NULL DEFAULT TRUE,
    allowed_values JSONB       NOT NULL DEFAULT '[]'::jsonb,
    sort_order     INTEGER     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_category_attribute          UNIQUE (family_id, attribute_key),
    CONSTRAINT chk_category_attribute_key_format CHECK (attribute_key ~ '^[a-z][a-z0-9_]*$'),
    CONSTRAINT chk_category_attribute_type   CHECK (data_type IN ('string','integer','boolean','decimal','enum','multi_enum'))
);

CREATE INDEX idx_category_attribute_family     ON category_attribute_config (family_id, is_filterable, sort_order);
CREATE INDEX idx_category_attribute_active_sort ON category_attribute_config (family_id, is_active, sort_order);
