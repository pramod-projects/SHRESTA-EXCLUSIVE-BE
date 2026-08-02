package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

public final class CustomerOrderDraftsMigration {

    private CustomerOrderDraftsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("customer_order_drafts")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE customer_order_drafts (
                    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                    draft_number        VARCHAR(56) NOT NULL UNIQUE,
                    customer_id         UUID        NOT NULL REFERENCES customer_accounts(id),
                    customer_email      VARCHAR(320) NOT NULL,
                    status              VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
                    cart_signature      VARCHAR(96) NOT NULL,
                    currency            CHAR(3)     NOT NULL DEFAULT 'INR',
                    subtotal_paise      BIGINT      NOT NULL CHECK (subtotal_paise >= 0),
                    delivery_paise      BIGINT      NOT NULL CHECK (delivery_paise >= 0),
                    discount_paise      BIGINT      NOT NULL DEFAULT 0 CHECK (discount_paise >= 0),
                    tax_paise           BIGINT      NOT NULL DEFAULT 0 CHECK (tax_paise >= 0),
                    total_paise         BIGINT      NOT NULL CHECK (total_paise >= 0),
                    delivery_mode       VARCHAR(40) NOT NULL DEFAULT 'STANDARD',
                    metadata            JSONB       NOT NULL DEFAULT '{}'::jsonb,
                    expires_at          TIMESTAMPTZ NOT NULL,
                    invalidated_at      TIMESTAMPTZ,
                    invalidation_reason VARCHAR(80),
                    converted_order_id  UUID        REFERENCES customer_orders(id),
                    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT chk_customer_order_draft_number_format CHECK (draft_number ~ '^SHRESTA-DRAFT-[0-9]{8}-[A-Z0-9]{8,16}$'),
                    CONSTRAINT chk_customer_order_draft_status        CHECK (status IN ('ACTIVE','INVALIDATED','EXPIRED','CONVERTED')),
                    CONSTRAINT chk_customer_order_draft_delivery_mode CHECK (delivery_mode IN ('STANDARD','EXPRESS','SAME_DAY')),
                    CONSTRAINT chk_customer_order_draft_total         CHECK (total_paise = subtotal_paise + delivery_paise + tax_paise - discount_paise),
                    CONSTRAINT chk_customer_order_draft_expiry_window CHECK (expires_at > created_at),
                    CONSTRAINT chk_customer_order_draft_invalidation  CHECK (
                        (status IN ('INVALIDATED','EXPIRED') AND invalidated_at IS NOT NULL AND invalidation_reason IS NOT NULL)
                        OR (status IN ('ACTIVE','CONVERTED') AND invalidation_reason IS NULL)
                    ),
                    CONSTRAINT chk_customer_order_draft_conversion    CHECK (
                        (status = 'CONVERTED' AND converted_order_id IS NOT NULL)
                        OR (status <> 'CONVERTED' AND converted_order_id IS NULL)
                    )
                )
                """,
                "CREATE INDEX idx_customer_order_drafts_customer_active    ON customer_order_drafts (customer_id, status, expires_at DESC)",
                "CREATE INDEX idx_customer_order_drafts_customer_signature ON customer_order_drafts (customer_id, cart_signature, status)",
                "CREATE INDEX idx_customer_order_drafts_converted_order    ON customer_order_drafts (converted_order_id)"
            ))
            .build();
    }
}
