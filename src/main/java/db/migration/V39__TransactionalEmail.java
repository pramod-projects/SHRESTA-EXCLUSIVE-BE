package db.migration;

import java.util.List;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import com.shrestaexclusive.platform.db.migration.framework.MigrationRunner;
import com.shrestaexclusive.platform.db.migration.tables.EmailDeliveryAttemptsMigration;
import com.shrestaexclusive.platform.db.migration.tables.EmailOutboxMigration;
import com.shrestaexclusive.platform.db.migration.tables.EmailWebhookEventsMigration;
import com.shrestaexclusive.platform.db.migration.tables.NotificationConfigurationMigration;

public class V39__TransactionalEmail extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        MigrationRunner.run(context.getConnection(), List.of(
                NotificationConfigurationMigration.transitionPlan(),
                EmailOutboxMigration.transitionPlan(),
                EmailDeliveryAttemptsMigration.transitionPlan(),
                EmailWebhookEventsMigration.transitionPlan()));
    }
}