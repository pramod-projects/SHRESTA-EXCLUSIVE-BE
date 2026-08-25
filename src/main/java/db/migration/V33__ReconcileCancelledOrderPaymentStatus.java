package db.migration;

import java.sql.Statement;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V33__ReconcileCancelledOrderPaymentStatus extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("""
                    WITH candidate_rows AS (
                        SELECT id, payment_status AS from_status
                        FROM customer_orders
                        WHERE status = 'CANCELLED'
                          AND payment_status IN ('PENDING', 'AUTHORIZED')
                    ),
                    updated AS (
                        UPDATE customer_orders target
                        SET payment_status = 'FAILED',
                            updated_at = now()
                        FROM candidate_rows source
                        WHERE target.id = source.id
                        RETURNING target.id
                    )
                    INSERT INTO customer_order_status_events (
                        order_id,
                        event_type,
                        from_status,
                        to_status,
                        actor_type,
                        actor_id,
                        note,
                        metadata,
                        created_at
                    )
                          SELECT source.id,
                           'PAYMENT_STATUS',
                              source.from_status,
                           'FAILED',
                           'SYSTEM',
                           'DATA_FIX_V33',
                           'Auto-reconciled cancelled order payment status during production hardening migration.',
                           '{}'::jsonb,
                           now()
                          FROM updated
                          JOIN candidate_rows source ON source.id = updated.id
                    """);
        }
    }
}
