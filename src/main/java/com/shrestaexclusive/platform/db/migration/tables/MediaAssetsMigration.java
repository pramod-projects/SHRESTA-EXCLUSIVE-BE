package com.shrestaexclusive.platform.db.migration.tables;

import java.util.List;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;

/**
 * media_assets includes the shresta_valid_asset_tags function that must exist before
 * the table is created. Both the function and the CREATE TABLE are in the same
 * transition so they apply atomically.
 */
public final class MediaAssetsMigration {

    private MediaAssetsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("media_assets")
            .transition(List.of(-1), 0, List.of(
                // Function must be created before the table that uses it in a CHECK constraint
                """
                CREATE OR REPLACE FUNCTION shresta_valid_asset_tags(tags JSONB) RETURNS BOOLEAN
                    LANGUAGE plpgsql IMMUTABLE STRICT AS $$
                DECLARE
                    t TEXT;
                BEGIN
                    IF jsonb_typeof(tags) <> 'array' THEN RETURN FALSE; END IF;
                    FOR t IN SELECT jsonb_array_elements_text(tags)
                    LOOP
                        IF t IS NULL OR t = '' OR t !~ '^[A-Z][A-Z0-9_]*$' THEN RETURN FALSE; END IF;
                    END LOOP;
                    RETURN TRUE;
                END;
                $$
                """,
                """
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
                    tags                      JSONB       NOT NULL DEFAULT '[]'::jsonb,
                    seo_title                 VARCHAR(180),
                    seo_description           VARCHAR(300),
                    processing_error          TEXT,
                    media_type               VARCHAR(32),
                    upload_expires_at         TIMESTAMPTZ,
                    uploaded_by               VARCHAR(320),
                    object_etag               VARCHAR(160),
                    archived_at               TIMESTAMPTZ,
                    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT chk_media_asset_key_format    CHECK (asset_key ~ '^[a-z][a-z0-9_-]*$'),
                    CONSTRAINT chk_media_asset_status        CHECK (status IN ('PENDING_UPLOAD','READY','FAILED','ARCHIVED')),
                    CONSTRAINT chk_media_asset_media_type    CHECK (media_type IS NULL OR media_type IN ('PRODUCT_IMAGE','PRODUCT_VIDEO','DISPLAY_IMAGE')),
                    CONSTRAINT chk_media_asset_version       CHECK (version > 0),
                    CONSTRAINT chk_media_asset_byte_size     CHECK (byte_size >= 0),
                    CONSTRAINT chk_media_assets_tag_contract CHECK (shresta_valid_asset_tags(tags))
                )
                """,
                "CREATE INDEX idx_media_assets_active_usage    ON media_assets (is_active, usage_type, asset_key)",
                "CREATE INDEX idx_media_assets_status          ON media_assets (status, is_active, updated_at DESC)",
                "CREATE INDEX idx_media_assets_family          ON media_assets (category_family_key, is_active, updated_at DESC)",
                "CREATE INDEX idx_media_assets_product         ON media_assets (product_sku, is_active, updated_at DESC)",
                "CREATE INDEX idx_media_assets_product_type    ON media_assets (category_product_type_key, is_active, updated_at DESC)",
                "CREATE INDEX idx_media_assets_tags            ON media_assets USING GIN (tags)"
            ))
            .transition(List.of(0), 1, List.of(
                "ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS media_type VARCHAR(32)",
                "ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_expires_at TIMESTAMPTZ",
                "ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS uploaded_by VARCHAR(320)",
                "ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS object_etag VARCHAR(160)",
                "ALTER TABLE media_assets DROP CONSTRAINT IF EXISTS chk_media_asset_status",
                "UPDATE media_assets SET status = 'READY' WHERE status IN ('UPLOADED', 'PROCESSING')",
                "ALTER TABLE media_assets ADD CONSTRAINT chk_media_asset_status CHECK (status IN ('PENDING_UPLOAD','READY','FAILED','ARCHIVED'))",
                "ALTER TABLE media_assets DROP CONSTRAINT IF EXISTS chk_media_asset_media_type",
                "ALTER TABLE media_assets ADD CONSTRAINT chk_media_asset_media_type CHECK (media_type IS NULL OR media_type IN ('PRODUCT_IMAGE','PRODUCT_VIDEO','DISPLAY_IMAGE'))",
                "DROP TABLE IF EXISTS media_asset_variants"
            ))
            .transition(List.of(1), 2, List.of(
                "ALTER TABLE media_assets DROP COLUMN IF EXISTS lqip_data_url"
            ))
            .transition(List.of(2), 3, List.of(
                "ALTER TABLE media_assets DROP CONSTRAINT IF EXISTS chk_media_asset_media_type",
                "ALTER TABLE media_assets ADD CONSTRAINT chk_media_asset_media_type CHECK (media_type IS NULL OR media_type IN ('PRODUCT_IMAGE','PRODUCT_VIDEO','DISPLAY_IMAGE','DISPLAY_VIDEO'))"
            ))
            .build();
    }
}
