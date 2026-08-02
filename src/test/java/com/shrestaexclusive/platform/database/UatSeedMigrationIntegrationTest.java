package com.shrestaexclusive.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("uat")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UatSeedMigrationIntegrationTest {

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shresta")
            .withUsername("shresta_app")
            .withPassword("change-me");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void uatProfileAppliesSharedCustomerAndAdminLoginSeed() {
        Map<String, Object> account = jdbcTemplate.queryForMap("""
                SELECT identity_email, otp_code, customer_enabled, admin_enabled, is_active
                FROM uat_seed_accounts
                WHERE identity_email = 'testuser@gmail.com'
                """);

        assertThat(account)
                .containsEntry("identity_email", "testuser@gmail.com")
                .containsEntry("otp_code", "123456")
                .containsEntry("customer_enabled", true)
                .containsEntry("admin_enabled", true)
                .containsEntry("is_active", true);

        Integer roleCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM uat_seed_admin_roles role
                JOIN uat_seed_accounts account ON account.id = role.account_id
                WHERE account.identity_email = 'testuser@gmail.com'
                AND role.admin_role IN ('SUPER_ADMIN', 'CHANGE_SUBMITTER', 'CHANGE_REVIEWER', 'CHANGE_MANAGER')
                """, Integer.class);

        assertThat(roleCount).isEqualTo(4);

        Map<String, Object> customer = jdbcTemplate.queryForMap("""
                SELECT primary_email, display_name, status
                FROM customer_accounts
                WHERE primary_email = 'testuser@gmail.com'
                """);

        assertThat(customer)
                .containsEntry("primary_email", "testuser@gmail.com")
                .containsEntry("display_name", "SHRESTA UAT Test User")
                .containsEntry("status", "ACTIVE");

        Integer identityCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM customer_auth_identities identity
                JOIN customer_accounts customer ON customer.id = identity.customer_id
                WHERE customer.primary_email = 'testuser@gmail.com'
                AND identity.identity_type = 'EMAIL'
                AND identity.identity_value = 'testuser@gmail.com'
                AND identity.is_verified = TRUE
                """, Integer.class);

        assertThat(identityCount).isEqualTo(1);
    }
}
