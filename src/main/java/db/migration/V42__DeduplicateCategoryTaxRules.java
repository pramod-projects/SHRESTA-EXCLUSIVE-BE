package db.migration;

import java.util.List;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import com.shrestaexclusive.platform.db.migration.framework.MigrationRunner;
import com.shrestaexclusive.platform.db.migration.tables.CategoryTaxConfigMigration;

public class V42__DeduplicateCategoryTaxRules extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        MigrationRunner.run(context.getConnection(), List.of(CategoryTaxConfigMigration.transitionPlan()));
    }
}