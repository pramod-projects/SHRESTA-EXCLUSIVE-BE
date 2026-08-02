-- demo_video_url merged from a later ALTER for clean installs.
CREATE TABLE storefront_home_items (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id     UUID        NOT NULL REFERENCES storefront_home_sections(id),
    item_key       VARCHAR(120) NOT NULL UNIQUE,
    family_key     VARCHAR(64),
    title          VARCHAR(180) NOT NULL,
    subtitle       VARCHAR(220),
    description    TEXT,
    cta_label      VARCHAR(80),
    cta_href       VARCHAR(240),
    sort_order     INTEGER     NOT NULL DEFAULT 0,
    is_featured    BOOLEAN     NOT NULL DEFAULT FALSE,
    media_asset_id UUID        REFERENCES media_assets(id),
    demo_video_url TEXT,
    metadata       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    is_active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_storefront_item_key_format CHECK (item_key ~ '^[a-z][a-z0-9_-]*$')
);

CREATE INDEX idx_storefront_home_items_section_sort ON storefront_home_items (section_id, is_active, sort_order);
CREATE INDEX idx_storefront_home_items_family       ON storefront_home_items (family_key, is_active);
