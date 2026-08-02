package com.shrestaexclusive.platform.db.migration.tables;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import java.util.List;

public final class StoreLocationsMigration {

    private StoreLocationsMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("store_locations")
            .transition(List.of(-1), 0, List.of(
                """
                CREATE TABLE store_locations (
                    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                    store_key             VARCHAR(100)   NOT NULL UNIQUE,
                    display_name          VARCHAR(180)   NOT NULL,
                    short_name            VARCHAR(80)    NOT NULL,
                    status                VARCHAR(32)    NOT NULL DEFAULT 'ACTIVE',
                    address_line1         VARCHAR(220)   NOT NULL,
                    address_line2         VARCHAR(220),
                    locality              VARCHAR(120)   NOT NULL,
                    city                  VARCHAR(120)   NOT NULL,
                    state                 VARCHAR(120)   NOT NULL,
                    postal_code           VARCHAR(20)    NOT NULL,
                    country_code          CHAR(2)        NOT NULL DEFAULT 'IN',
                    phone                 VARCHAR(40),
                    whatsapp_number       VARCHAR(40),
                    email                 VARCHAR(160),
                    latitude              NUMERIC(9,6)   NOT NULL,
                    longitude             NUMERIC(9,6)   NOT NULL,
                    supported_family_keys JSONB          NOT NULL DEFAULT '[]'::jsonb,
                    service_modes         JSONB          NOT NULL DEFAULT '[]'::jsonb,
                    highlights            JSONB          NOT NULL DEFAULT '[]'::jsonb,
                    opening_hours         JSONB          NOT NULL DEFAULT '[]'::jsonb,
                    fulfillment           JSONB          NOT NULL DEFAULT '{}'::jsonb,
                    is_active             BOOLEAN        NOT NULL DEFAULT TRUE,
                    sort_order            INTEGER        NOT NULL DEFAULT 0,
                    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
                    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
                    CONSTRAINT chk_store_location_key_format  CHECK (store_key ~ '^[a-z][a-z0-9_-]*$'),
                    CONSTRAINT chk_store_location_status      CHECK (status IN ('ACTIVE','SERVICE_ONLY','OPENING_SOON')),
                    CONSTRAINT chk_store_location_latitude    CHECK (latitude  >= -90  AND latitude  <= 90),
                    CONSTRAINT chk_store_location_longitude   CHECK (longitude >= -180 AND longitude <= 180)
                )
                """,
                "CREATE INDEX idx_store_locations_active_city  ON store_locations (is_active, city, sort_order)",
                "CREATE INDEX idx_store_locations_state_city   ON store_locations (state, city)",
                "CREATE INDEX idx_store_locations_modes        ON store_locations USING GIN (service_modes)",
                "CREATE INDEX idx_store_locations_families     ON store_locations USING GIN (supported_family_keys)"
            ))
            .build();
    }
}
