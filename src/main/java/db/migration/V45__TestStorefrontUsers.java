package db.migration;

import java.util.List;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import com.shrestaexclusive.platform.db.migration.framework.MigrationRunner;
import com.shrestaexclusive.platform.db.migration.tables.CustomerAccountsMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerOrderDraftsMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerOrdersMigration;
import com.shrestaexclusive.platform.db.migration.tables.StorefrontTestAccountsMigration;

public class V45__TestStorefrontUsers extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        MigrationRunner.run(context.getConnection(), List.of(
                CustomerAccountsMigration.transitionPlan(),
                CustomerOrdersMigration.transitionPlan(),
                CustomerOrderDraftsMigration.transitionPlan(),
                StorefrontTestAccountsMigration.transitionPlan()));
    }
}
