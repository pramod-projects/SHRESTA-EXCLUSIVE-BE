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
);

CREATE INDEX idx_category_tax_family_active ON category_tax_config (family_id, is_active, effective_from);
