package com.shrestaexclusive.platform.db.migration.tables;

import java.util.List;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;

public final class CategoryTaxConfigMigration {

    private CategoryTaxConfigMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("category_tax_config")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE category_tax_config (
                    id                    UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
                    family_id             UUID    NOT NULL REFERENCES category_family_config(id),
                    hsn_code              VARCHAR(32)  NOT NULL,
                    gst_rate_basis_points INTEGER NOT NULL,
                    effective_from        DATE    NOT NULL,
                    effective_to          DATE,
                    is_active             BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT chk_category_tax_bps   CHECK (gst_rate_basis_points >= 0 AND gst_rate_basis_points <= 2800),
                    CONSTRAINT chk_category_tax_dates CHECK (effective_to IS NULL OR effective_to > effective_from)
                )
                """,
                "CREATE INDEX idx_category_tax_family_active ON category_tax_config (family_id, is_active, effective_from)"
            ))
            .transition(List.of(0), 1, List.of(
                """
                DELETE FROM category_tax_config duplicate
                USING category_tax_config retained
                WHERE duplicate.family_id = retained.family_id
                  AND duplicate.hsn_code = retained.hsn_code
                  AND duplicate.effective_from = retained.effective_from
                  AND (duplicate.updated_at, duplicate.created_at, duplicate.id)
                      < (retained.updated_at, retained.created_at, retained.id)
                """,
                """
                ALTER TABLE category_tax_config
                ADD CONSTRAINT uq_category_tax_family_hsn_effective
                UNIQUE (family_id, hsn_code, effective_from)
                """
            ))
            .build();
    }
}
