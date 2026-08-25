package com.shrestaexclusive.platform.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MigrationSqlLintTest {

    private static final Path CATEGORY_FAMILIES_SEED = Path.of(
            "src/main/resources/db/seed/category/families.json");
    private static final Path APPLICATION_CONFIG = Path.of("src/main/resources/application.yml");
    private static final Path LEGACY_UAT_LOGIN_SEED_MIGRATION = Path.of(
            "src/main/resources/db/dev-uat-migration/R__seed_uat_login_accounts.sql");
    private static final Path DEV_LOGIN_SEED_JSON = Path.of(
            "src/main/resources/db/seed/auth/dev-accounts.json");

    @Test
    void categorySeedContainsPrimarySilkFamily() throws IOException {
        String seedJson = Files.readString(CATEGORY_FAMILIES_SEED);

        assertThat(seedJson)
                .contains("\"family_key\": \"silk_saree\"")
                .contains("\"display_name\": \"Sarees\"");
    }

    @Test
    void uatLoginSeedKeepsSharedTestAccountAndSixDigitOtp() throws IOException {
        String seedJson = Files.readString(DEV_LOGIN_SEED_JSON);

        assertThat(seedJson)
                .contains("\"primary_email\": \"testuser@gmail.com\"")
                .contains("\"otp_code\": \"123456\"")
                .contains("\"display_name\": \"SHRESTA UAT Test User\"");
    }

    @Test
    void uatLoginSeedIsNotPartOfProductionFlywayLocation() throws IOException {
        String config = Files.readString(APPLICATION_CONFIG);

        assertThat(LEGACY_UAT_LOGIN_SEED_MIGRATION).doesNotExist();
        assertThat(config)
                .contains("locations: ${SHRESTA_FLYWAY_LOCATIONS:classpath:db/migration}")
                .contains("on-profile: \"local | dev | uat\"")
                .doesNotContain("classpath:db/dev-uat-migration");
    }
}
