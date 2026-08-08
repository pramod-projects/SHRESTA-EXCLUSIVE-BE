package db.migration;

import java.util.List;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import com.shrestaexclusive.platform.db.migration.framework.MigrationRunner;
import com.shrestaexclusive.platform.db.migration.tables.CustomerPaymentTransactionsMigration;

public class V31__CustomerPaymentTransactions extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        MigrationRunner.run(context.getConnection(), List.of(
            CustomerPaymentTransactionsMigration.transitionPlan()
        ));
    }
}
