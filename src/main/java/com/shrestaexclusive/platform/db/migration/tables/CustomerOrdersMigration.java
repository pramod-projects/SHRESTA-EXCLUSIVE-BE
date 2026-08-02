package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

public final class CustomerOrdersMigration {

    private CustomerOrdersMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("customer_orders")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE customer_orders (
                    id                        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                    order_number              VARCHAR(40) NOT NULL UNIQUE,
                    customer_id               UUID        NOT NULL REFERENCES customer_accounts(id),
                    customer_email            VARCHAR(320) NOT NULL,
                    status                    VARCHAR(40) NOT NULL,
                    payment_status            VARCHAR(40) NOT NULL,
                    fulfillment_status        VARCHAR(40) NOT NULL,
                    currency                  CHAR(3)     NOT NULL DEFAULT 'INR',
                    subtotal_paise            BIGINT      NOT NULL CHECK (subtotal_paise >= 0),
                    delivery_paise            BIGINT      NOT NULL CHECK (delivery_paise >= 0),
                    discount_paise            BIGINT      NOT NULL DEFAULT 0 CHECK (discount_paise >= 0),
                    tax_paise                 BIGINT      NOT NULL DEFAULT 0 CHECK (tax_paise >= 0),
                    total_paise               BIGINT      NOT NULL CHECK (total_paise >= 0),
                    delivery_mode             VARCHAR(40) NOT NULL,
                    payment_method            VARCHAR(40) NOT NULL,
                    contact_snapshot          JSONB       NOT NULL,
                    shipping_address_snapshot JSONB       NOT NULL,
                    metadata                  JSONB       NOT NULL DEFAULT '{}'::jsonb,
                    placed_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
                    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT chk_customer_order_number_format      CHECK (order_number ~ '^SHRESTA-[0-9]{8}-[A-Z0-9]{8,16}$'),
                    CONSTRAINT chk_customer_order_status             CHECK (status IN ('PLACED','PAYMENT_PENDING','CONFIRMED','PACKING','READY_FOR_PICKUP','OUT_FOR_DELIVERY','DELIVERED','CANCELLED','PAYMENT_FAILED')),
                    CONSTRAINT chk_customer_order_payment_status     CHECK (payment_status IN ('PENDING','AUTHORIZED','CAPTURED','FAILED','REFUNDED')),
                    CONSTRAINT chk_customer_order_fulfillment_status CHECK (fulfillment_status IN ('PENDING','ALLOCATED','PACKING','READY','SHIPPED','DELIVERED','CANCELLED')),
                    CONSTRAINT chk_customer_order_delivery_mode      CHECK (delivery_mode IN ('STANDARD','EXPRESS','SAME_DAY')),
                    CONSTRAINT chk_customer_order_payment_method     CHECK (payment_method IN ('UPI','CARD','NETBANKING')),
                    CONSTRAINT chk_customer_order_total              CHECK (total_paise = subtotal_paise + delivery_paise + tax_paise - discount_paise)
                )
                """,
                "CREATE INDEX idx_customer_orders_customer_created ON customer_orders (customer_id, created_at DESC)",
                "CREATE INDEX idx_customer_orders_status_created   ON customer_orders (status, created_at DESC)"
            ))
            .build();
    }
}
