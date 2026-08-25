package com.shrestaexclusive.platform.db.migration.tables;

import java.util.List;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;

public final class ProductMediaReservationsMigration {

    private ProductMediaReservationsMigration() {
    }

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("product_media_reservations")
                .transition(List.of(-1), 0, List.of(
                        """
                        CREATE TABLE product_media_reservations (
                            id UUID PRIMARY KEY,
                            product_id VARCHAR(120) NOT NULL UNIQUE,
                            reserved_by VARCHAR(320) NOT NULL,
                            status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                            expires_at TIMESTAMPTZ NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            consumed_at TIMESTAMPTZ,
                            CONSTRAINT chk_product_media_reservation_status
                                CHECK (status IN ('ACTIVE', 'SUBMITTED', 'CONSUMED', 'EXPIRED'))
                        )
                        """,
                        "CREATE INDEX idx_product_media_reservations_active ON product_media_reservations (product_id, reserved_by, expires_at) WHERE status = 'ACTIVE'"
                ))
                    .transition(List.of(0), 1, List.of(
                        "ALTER TABLE product_media_reservations DROP CONSTRAINT IF EXISTS chk_product_media_reservation_status",
                        "ALTER TABLE product_media_reservations ADD CONSTRAINT chk_product_media_reservation_status CHECK (status IN ('ACTIVE', 'SUBMITTED', 'CONSUMED', 'EXPIRED'))"
                    ))
                .build();
    }
}
