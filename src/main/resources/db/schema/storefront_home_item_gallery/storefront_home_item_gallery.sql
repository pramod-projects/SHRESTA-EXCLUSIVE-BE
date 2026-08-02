-- Up to 4 additional gallery images per product item (sort_order 1–4).
CREATE TABLE storefront_home_item_gallery (
    id             UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id        UUID    NOT NULL REFERENCES storefront_home_items(id) ON DELETE CASCADE,
    media_asset_id UUID    NOT NULL REFERENCES media_assets(id),
    sort_order     INTEGER NOT NULL CHECK (sort_order BETWEEN 1 AND 4),
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_item_gallery_active_slot UNIQUE (item_id, sort_order)
);

CREATE INDEX idx_storefront_item_gallery_item ON storefront_home_item_gallery (item_id, is_active, sort_order);
