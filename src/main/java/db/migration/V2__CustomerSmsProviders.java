package db.migration;

import com.shrestaexclusive.platform.db.migration.framework.MigrationRunner;
import com.shrestaexclusive.platform.db.migration.tables.CustomerSmsAttemptsMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerSmsMessagesMigration;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2__CustomerSmsProviders extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        MigrationRunner.run(context.getConnection(), List.of(
            CustomerSmsMessagesMigration.transitionPlan(),
            CustomerSmsAttemptsMigration.transitionPlan()
        ));
    }
}
