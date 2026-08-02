package db.migration;

import com.shrestaexclusive.platform.db.migration.framework.MigrationRunner;
import com.shrestaexclusive.platform.db.migration.tables.AdminChangeRequestsMigration;
import com.shrestaexclusive.platform.db.migration.tables.CategoryAttributeConfigMigration;
import com.shrestaexclusive.platform.db.migration.tables.CategoryFamilyConfigMigration;
import com.shrestaexclusive.platform.db.migration.tables.CategoryFilterConfigMigration;
import com.shrestaexclusive.platform.db.migration.tables.CategoryProductTypeConfigMigration;
import com.shrestaexclusive.platform.db.migration.tables.CategoryStylingConfigMigration;
import com.shrestaexclusive.platform.db.migration.tables.CategoryTaxConfigMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerAccountsMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerAuthIdentitiesMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerChatMessagesMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerChatSessionsMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerOrderDraftItemsMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerOrderDraftsMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerOrderItemsMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerOrderStatusEventsMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerOrdersMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerOtpChallengesMigration;
import com.shrestaexclusive.platform.db.migration.tables.CustomerSessionsMigration;
import com.shrestaexclusive.platform.db.migration.tables.ExtensionsMigration;
import com.shrestaexclusive.platform.db.migration.tables.UatSeedAccountsMigration;
import com.shrestaexclusive.platform.db.migration.tables.MediaAssetVariantsMigration;
import com.shrestaexclusive.platform.db.migration.tables.MediaAssetsMigration;
import com.shrestaexclusive.platform.db.migration.tables.StoreLocationsMigration;
import com.shrestaexclusive.platform.db.migration.tables.StorefrontHomeItemGalleryMigration;
import com.shrestaexclusive.platform.db.migration.tables.StorefrontHomeItemsMigration;
import com.shrestaexclusive.platform.db.migration.tables.StorefrontHomeSectionsMigration;
import com.shrestaexclusive.platform.db.migration.tables.StorefrontStoreSectionsMigration;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Flyway entry point — runs the full schema migration.
 *
 * Matches: mainMigration / executeTransitionPlanList mainTransitionPlanList
 * in Server.Migrations.Run (Haskell euler-lsp).
 *
 * Each table owns its migration history in its *Migration.transitionPlan() class.
 * MigrationRunner tracks per-table version in shresta_table_migration_versions and
 * applies only the transitions that are pending for the current DB state.
 *
 * To add a future schema change:
 *   1. Add a new .transition(List.of(N), N+1, ...) in the relevant table's Migration class.
 *   2. Create a new V2__<description>.java Flyway migration that calls
 *      MigrationRunner.run(conn, List.of(<ThatTableMigration>.transitionPlan()))
 *   The runner will skip already-applied transitions and execute only the new one.
 */
public class V1__Schema extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        MigrationRunner.run(context.getConnection(), List.of(

            // ── 0. Extensions ────────────────────────────────────────────────
            ExtensionsMigration.transitionPlan(),

            // ── 1. Category ──────────────────────────────────────────────────
            CategoryFamilyConfigMigration.transitionPlan(),
            CategoryProductTypeConfigMigration.transitionPlan(),
            CategoryAttributeConfigMigration.transitionPlan(),
            CategoryFilterConfigMigration.transitionPlan(),
            CategoryTaxConfigMigration.transitionPlan(),
            CategoryStylingConfigMigration.transitionPlan(),

            // ── 2. Media ─────────────────────────────────────────────────────
            MediaAssetsMigration.transitionPlan(),
            MediaAssetVariantsMigration.transitionPlan(),

            // ── 3. Storefront home ───────────────────────────────────────────
            StorefrontHomeSectionsMigration.transitionPlan(),
            StorefrontHomeItemsMigration.transitionPlan(),
            StorefrontHomeItemGalleryMigration.transitionPlan(),

            // ── 4. Store locator ─────────────────────────────────────────────
            StorefrontStoreSectionsMigration.transitionPlan(),
            StoreLocationsMigration.transitionPlan(),

            // ── 5. Admin ─────────────────────────────────────────────────────
            AdminChangeRequestsMigration.transitionPlan(),

            // ── 6. Customer identity ─────────────────────────────────────────
            CustomerAccountsMigration.transitionPlan(),
            CustomerAuthIdentitiesMigration.transitionPlan(),
            CustomerOtpChallengesMigration.transitionPlan(),
            CustomerSessionsMigration.transitionPlan(),
            UatSeedAccountsMigration.transitionPlan(),   // UAT/dev static-OTP control table

            // ── 7. Customer chat ─────────────────────────────────────────────
            CustomerChatSessionsMigration.transitionPlan(),
            CustomerChatMessagesMigration.transitionPlan(),

            // ── 8. Customer orders ───────────────────────────────────────────
            CustomerOrdersMigration.transitionPlan(),
            CustomerOrderItemsMigration.transitionPlan(),
            CustomerOrderStatusEventsMigration.transitionPlan(),

            // ── 9. Customer order drafts ─────────────────────────────────────
            CustomerOrderDraftsMigration.transitionPlan(),
            CustomerOrderDraftItemsMigration.transitionPlan()
        ));
    }
}
