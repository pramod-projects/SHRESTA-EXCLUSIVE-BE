CREATE TABLE notification_configuration_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_type VARCHAR(48) NOT NULL REFERENCES notification_configuration(notification_type),
    previous_enabled BOOLEAN NOT NULL,
    new_enabled BOOLEAN NOT NULL,
    changed_by VARCHAR(160) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_configuration_audit_type_time
ON notification_configuration_audit (notification_type, changed_at DESC);