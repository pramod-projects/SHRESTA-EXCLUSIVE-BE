package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

public final class MediaAssetVariantsMigration {

    private MediaAssetVariantsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("media_asset_variants")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE media_asset_variants (
                    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                    asset_id     UUID        NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
                    variant_key  VARCHAR(40) NOT NULL,
                    format       VARCHAR(16) NOT NULL,
                    width_px     INTEGER     NOT NULL CHECK (width_px > 0),
                    height_px    INTEGER     NOT NULL CHECK (height_px > 0),
                    byte_size    BIGINT      NOT NULL DEFAULT 0 CHECK (byte_size >= 0),
                    storage_key  VARCHAR(700) NOT NULL,
                    url_path     VARCHAR(700) NOT NULL,
                    content_type VARCHAR(120) NOT NULL,
                    is_active    BOOLEAN     NOT NULL DEFAULT TRUE,
                    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT uq_media_asset_variant UNIQUE (asset_id, variant_key, format),
                    CONSTRAINT chk_media_variant_format CHECK (format IN ('jpg','jpeg','png','webp','avif')),
                    CONSTRAINT chk_media_variant_key   CHECK (variant_key IN ('original','thumbnail','small','medium','large'))
                )
                """,
                "CREATE INDEX idx_media_asset_variants_asset ON media_asset_variants (asset_id, is_active, width_px)"
            ))
            .build();
    }
}
