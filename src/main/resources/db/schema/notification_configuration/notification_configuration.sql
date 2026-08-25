CREATE TABLE notification_configuration (
    notification_type VARCHAR(48) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    production_locked BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    update_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);