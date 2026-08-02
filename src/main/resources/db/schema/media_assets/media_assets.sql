-- All ALTER TABLE columns from V3, V15, and the tag-contract constraint from V19 merged here.
-- Validates that tags is a JSON array of non-empty uppercase identifiers (e.g. ["BRAND","SAREE"]).
-- Value-level restriction is enforced at the application layer; here we only enforce structure and format.
CREATE OR REPLACE FUNCTION shresta_valid_asset_tags(tags JSONB) RETURNS BOOLEAN
    LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    t TEXT;
BEGIN
    IF jsonb_typeof(tags) <> 'array' THEN RETURN FALSE; END IF;
    FOR t IN SELECT jsonb_array_elements_text(tags)
    LOOP
        -- Each tag must be a non-empty uppercase alphanumeric string (underscores allowed)
        IF t IS NULL OR t = '' OR t !~ '^[A-Z][A-Z0-9_]*$' THEN RETURN FALSE; END IF;
    END LOOP;
    RETURN TRUE;
END;
$$;
CREATE TABLE media_assets (
    id                        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_key                 VARCHAR(120) NOT NULL UNIQUE,
    asset_url                 VARCHAR(700) NOT NULL,
    alt_text                  VARCHAR(240) NOT NULL,
    width_px                  INTEGER     NOT NULL CHECK (width_px > 0),
    height_px                 INTEGER     NOT NULL CHECK (height_px > 0),
    delivery_mode             VARCHAR(40) NOT NULL DEFAULT 's3-compatible',
    usage_type                VARCHAR(40) NOT NULL,
    metadata                  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    is_active                 BOOLEAN     NOT NULL DEFAULT TRUE,
    original_filename         VARCHAR(240),
    storage_provider          VARCHAR(40) NOT NULL DEFAULT 's3-compatible',
    storage_key               VARCHAR(700),
    category_family_key       VARCHAR(64),
    category_product_type_key VARCHAR(80),
    product_sku               VARCHAR(80),
    content_type              VARCHAR(120),
    byte_size                 BIGINT      NOT NULL DEFAULT 0,
    checksum_sha256           VARCHAR(64),
    status                    VARCHAR(32) NOT NULL DEFAULT 'READY',
    version                   INTEGER     NOT NULL DEFAULT 1,
    lqip_data_url             TEXT,
    tags                      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    seo_title                 VARCHAR(180),
    seo_description           VARCHAR(300),
    processing_error          TEXT,
    archived_at               TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_media_asset_key_format    CHECK (asset_key ~ '^[a-z][a-z0-9_-]*$'),
    CONSTRAINT chk_media_asset_status        CHECK (status IN ('UPLOADED','PROCESSING','READY','FAILED','ARCHIVED')),
    CONSTRAINT chk_media_asset_version       CHECK (version > 0),
    CONSTRAINT chk_media_asset_byte_size     CHECK (byte_size >= 0),
    CONSTRAINT chk_media_assets_tag_contract CHECK (shresta_valid_asset_tags(tags))
);

CREATE INDEX idx_media_assets_active_usage    ON media_assets (is_active, usage_type, asset_key);
CREATE INDEX idx_media_assets_status          ON media_assets (status, is_active, updated_at DESC);
CREATE INDEX idx_media_assets_family          ON media_assets (category_family_key, is_active, updated_at DESC);
CREATE INDEX idx_media_assets_product         ON media_assets (product_sku, is_active, updated_at DESC);
CREATE INDEX idx_media_assets_product_type    ON media_assets (category_product_type_key, is_active, updated_at DESC);
CREATE INDEX idx_media_assets_tags            ON media_assets USING GIN (tags);
