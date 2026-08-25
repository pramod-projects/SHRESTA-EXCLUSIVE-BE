package com.shrestaexclusive.platform.database;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shrestaexclusive.platform.db.migration.framework.MigrationRunner;
import com.shrestaexclusive.platform.db.migration.framework.TransitionPlan;
import com.shrestaexclusive.platform.db.migration.tables.CategoryTaxConfigMigration;

@Testcontainers
class CategoryTaxMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shresta")
            .withUsername("shresta")
            .withPassword("shresta");

    @Test
    void versionOneDeduplicatesTaxRulesAndEnforcesTheirNaturalKey() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        String schema = "category_tax_upgrade_" + UUID.randomUUID().toString().replace("-", "");

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("""
                    CREATE TABLE category_family_config (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        family_key VARCHAR(80) NOT NULL UNIQUE
                    )
                    """);
            statement.execute("INSERT INTO category_family_config (family_key) VALUES ('silk_saree')");

            var initialTransition = CategoryTaxConfigMigration.transitionPlan().transitions().getFirst();
            TransitionPlan versionZeroPlan = TransitionPlan.forTable("category_tax_config")
                    .transition(initialTransition.fromVersions(), initialTransition.toVersion(), initialTransition.sql())
                    .build();
            MigrationRunner.run(connection, List.of(versionZeroPlan));

            statement.execute("""
                    INSERT INTO category_tax_config (family_id, hsn_code, gst_rate_basis_points, effective_from)
                    SELECT family.id, '5007', 500, DATE '2026-08-02'
                    FROM category_family_config family, generate_series(1, 39)
                    WHERE family.family_key = 'silk_saree'
                    """);

            JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            assertThat(jdbc.sql("SELECT count(*) FROM category_tax_config").query(Long.class).single()).isEqualTo(39);

            MigrationRunner.run(connection, List.of(CategoryTaxConfigMigration.transitionPlan()));

            assertThat(jdbc.sql("SELECT count(*) FROM category_tax_config").query(Long.class).single()).isOne();
            assertThat(jdbc.sql("""
                    SELECT version FROM shresta_table_migration_versions
                    WHERE table_name = 'category_tax_config'
                    """).query(Integer.class).single()).isEqualTo(1);
            assertThatThrownBy(() -> jdbc.sql("""
                    INSERT INTO category_tax_config (family_id, hsn_code, gst_rate_basis_points, effective_from)
                    SELECT id, '5007', 500, DATE '2026-08-02'
                    FROM category_family_config WHERE family_key = 'silk_saree'
                    """).update()).hasMessageContaining("uq_category_tax_family_hsn_effective");
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
