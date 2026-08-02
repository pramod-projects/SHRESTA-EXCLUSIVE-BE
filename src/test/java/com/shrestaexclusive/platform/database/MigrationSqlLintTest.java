package com.shrestaexclusive.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationSqlLintTest {

    private static final Path CATEGORY_MIGRATION = Path.of(
            "src/main/resources/db/migration/V1__category_configuration_foundation.sql");
    private static final Path APPLICATION_CONFIG = Path.of("src/main/resources/application.yml");
    private static final Path GLOBAL_UAT_LOGIN_SEED_MIGRATION = Path.of(
            "src/main/resources/db/migration/V9__uat_seed_login_accounts.sql");
    private static final Path UAT_LOGIN_SEED_MIGRATION = Path.of(
            "src/main/resources/db/dev-uat-migration/R__seed_uat_login_accounts.sql");

    @Test
    void categorySeedQueriesQualifyOverlappingColumnNames() throws IOException {
        String sql = Files.readString(CATEGORY_MIGRATION);

        assertThat(sql)
                .doesNotContain("SELECT id, type_key, display_name, sort_order")
                .doesNotContain("SELECT id, attribute_key, display_name, data_type")
                .doesNotContain("SELECT id, hsn_code, gst_rate_basis_points")
                .doesNotContain("SELECT id, occasion_key, display_name");
        assertThat(sql)
                .contains("SELECT family.id, seed.type_key, seed.display_name, seed.sort_order")
                .contains("SELECT family.id, seed.attribute_key, seed.display_name, seed.data_type")
                .contains("SELECT family.id, seed.hsn_code, seed.gst_rate_basis_points")
                .contains("SELECT family.id, seed.occasion_key, seed.display_name");
    }

    @Test
    void uatLoginSeedKeepsSharedTestAccountAndSixDigitOtp() throws IOException {
        String sql = Files.readString(UAT_LOGIN_SEED_MIGRATION);

        assertThat(sql)
                .contains("'testuser@gmail.com'")
                .contains("'123456'")
                .contains("chk_uat_seed_otp_code CHECK (otp_code ~ '^[0-9]{6}$')")
                .contains("'SUPER_ADMIN'")
                .contains("'CHANGE_SUBMITTER'")
                .contains("'CHANGE_REVIEWER'")
                .contains("'CHANGE_MANAGER'");
    }

    @Test
    void uatLoginSeedIsNotPartOfProductionFlywayLocation() throws IOException {
        String config = Files.readString(APPLICATION_CONFIG);

        assertThat(GLOBAL_UAT_LOGIN_SEED_MIGRATION).doesNotExist();
        assertThat(config)
                .contains("locations: ${SHRESTA_FLYWAY_LOCATIONS:classpath:db/migration}")
                .contains("on-profile: \"local | dev | uat\"")
                .contains("classpath:db/dev-uat-migration");
        assertThat(UAT_LOGIN_SEED_MIGRATION.getFileName().toString()).startsWith("R__");
    }
}
