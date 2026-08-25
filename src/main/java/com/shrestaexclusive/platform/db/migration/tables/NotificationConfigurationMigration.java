package com.shrestaexclusive.platform.db.migration.tables;

import java.util.List;

import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;

public final class NotificationConfigurationMigration {
    private NotificationConfigurationMigration() {}

    public static TransitionPlan transitionPlan() {
        return TransitionPlan.forTable("notification_configuration")
                .transition(List.of(-1), 0, List.of("""
                    CREATE TABLE notification_configuration (
                        notification_type VARCHAR(48) PRIMARY KEY,
                        enabled BOOLEAN NOT NULL DEFAULT TRUE,
                        production_locked BOOLEAN NOT NULL DEFAULT FALSE,
                        version BIGINT NOT NULL DEFAULT 0,
                        updated_by VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
                        update_reason VARCHAR(500),
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """, """
                    INSERT INTO notification_configuration (notification_type, production_locked) VALUES
                    ('OTP', TRUE), ('EMAIL_VERIFICATION', TRUE), ('PASSWORD_RESET', TRUE), ('ACCOUNT_SECURITY', TRUE),
                    ('ORDER_CONFIRMATION', FALSE), ('ORDER_CANCELLED', FALSE), ('ORDER_SHIPPED', FALSE),
                    ('ORDER_OUT_FOR_DELIVERY', FALSE), ('ORDER_DELIVERED', FALSE), ('PAYMENT_SUCCESS', FALSE),
                    ('PAYMENT_FAILED', FALSE), ('REFUND_INITIATED', FALSE), ('REFUND_COMPLETED', FALSE)
                    """))
                .transition(List.of(0), 1, List.of("""
                    CREATE TABLE notification_configuration_audit (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        notification_type VARCHAR(48) NOT NULL REFERENCES notification_configuration(notification_type),
                        previous_enabled BOOLEAN NOT NULL,
                        new_enabled BOOLEAN NOT NULL,
                        changed_by VARCHAR(160) NOT NULL,
                        change_reason VARCHAR(500) NOT NULL,
                        changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """, """
                    CREATE INDEX idx_notification_configuration_audit_type_time
                    ON notification_configuration_audit (notification_type, changed_at DESC)
                    """))
                .build();
    }
}