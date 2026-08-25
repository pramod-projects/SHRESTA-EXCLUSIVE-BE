package db.migration;

import java.sql.Statement;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V35__AdminUserRoleRenameAndConstraint extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("""
                    UPDATE admin_users
                    SET role = 'CHANGE_APPROVER',
                        updated_at = now()
                    WHERE role = 'CHANGE_REVIEWER'
                    """);

            statement.execute("""
                    ALTER TABLE admin_users
                    DROP CONSTRAINT IF EXISTS chk_admin_users_role
                    """);

            statement.execute("""
                    ALTER TABLE admin_users
                    ADD CONSTRAINT chk_admin_users_role
                    CHECK (role IN ('SUPER_ADMIN','CHANGE_SUBMITTER','CHANGE_APPROVER','CHANGE_MANAGER','CHANGE_ADMIN'))
                    """);
        }
    }
}
