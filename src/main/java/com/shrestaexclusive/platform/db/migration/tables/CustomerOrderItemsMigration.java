package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

public final class CustomerOrderItemsMigration {

    private CustomerOrderItemsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("customer_order_items")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE customer_order_items (
                    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                    order_id               UUID        NOT NULL REFERENCES customer_orders(id) ON DELETE CASCADE,
                    product_item_key       VARCHAR(120) NOT NULL,
                    product_sku            VARCHAR(80) NOT NULL,
                    product_slug           VARCHAR(180) NOT NULL,
                    product_name           VARCHAR(180) NOT NULL,
                    family_key             VARCHAR(64) NOT NULL,
                    product_type           VARCHAR(80) NOT NULL,
                    quantity               INTEGER     NOT NULL CHECK (quantity BETWEEN 1 AND 99),
                    unit_price_paise       BIGINT      NOT NULL CHECK (unit_price_paise >= 0),
                    compare_at_price_paise BIGINT      NOT NULL DEFAULT 0 CHECK (compare_at_price_paise >= 0),
                    line_total_paise       BIGINT      NOT NULL CHECK (line_total_paise >= 0),
                    media_asset_key        VARCHAR(120),
                    media_url              VARCHAR(700),
                    media_alt_text         VARCHAR(240),
                    metadata               JSONB       NOT NULL DEFAULT '{}'::jsonb,
                    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT chk_customer_order_line_total CHECK (line_total_paise = unit_price_paise * quantity)
                )
                """,
                "CREATE INDEX idx_customer_order_items_order   ON customer_order_items (order_id)",
                "CREATE INDEX idx_customer_order_items_product ON customer_order_items (product_item_key)"
            ))
            .build();
    }
}
