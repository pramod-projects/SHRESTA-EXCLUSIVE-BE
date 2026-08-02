package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

public final class CustomerOrderStatusEventsMigration {

    private CustomerOrderStatusEventsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("customer_order_status_events")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE customer_order_status_events (
                    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                    order_id    UUID        NOT NULL REFERENCES customer_orders(id) ON DELETE CASCADE,
                    event_type  VARCHAR(40) NOT NULL,
                    from_status VARCHAR(40),
                    to_status   VARCHAR(40) NOT NULL,
                    actor_type  VARCHAR(40) NOT NULL,
                    actor_id    VARCHAR(120),
                    note        TEXT,
                    metadata    JSONB       NOT NULL DEFAULT '{}'::jsonb,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT chk_customer_order_status_event_type CHECK (event_type IN ('ORDER_STATUS','PAYMENT_STATUS','FULFILLMENT_STATUS')),
                    CONSTRAINT chk_customer_order_status_actor      CHECK (actor_type IN ('CUSTOMER','SYSTEM','ADMIN','PAYMENT_GATEWAY'))
                )
                """,
                "CREATE INDEX idx_customer_order_status_events_order ON customer_order_status_events (order_id, created_at)"
            ))
            .build();
    }
}
