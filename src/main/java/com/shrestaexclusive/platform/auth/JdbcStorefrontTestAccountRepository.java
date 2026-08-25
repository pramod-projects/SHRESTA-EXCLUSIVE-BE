package com.shrestaexclusive.platform.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStorefrontTestAccountRepository {

    private final JdbcClient jdbcClient;

    public JdbcStorefrontTestAccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean isTestAccountIdentity(String identityValue) {
        return jdbcClient.sql("""
                        SELECT customer.is_test AS is_test
                        FROM customer_auth_identities identity
                        JOIN customer_accounts customer ON customer.id = identity.customer_id
                        WHERE identity.identity_value = :identity
                          AND customer.status <> 'DELETED'
                        LIMIT 1
                        """)
                .param("identity", identityValue)
                .query(Boolean.class)
                .optional()
                .orElse(Boolean.FALSE);
    }

    public Optional<StorefrontTestAccount> findByCustomerId(UUID customerId) {
        return jdbcClient.sql("""
                        SELECT otp_hash, is_active, otp_revealed_at
                        FROM storefront_test_accounts
                        WHERE customer_id = :customerId
                        LIMIT 1
                        """)
                .param("customerId", customerId)
                .query((rs, rowNum) -> new StorefrontTestAccount(
                        rs.getString("otp_hash"),
                        rs.getBoolean("is_active"),
                        rs.getTimestamp("otp_revealed_at") == null
                                ? null
                                : rs.getTimestamp("otp_revealed_at").toInstant()))
                .optional();
    }

    public record StorefrontTestAccount(String otpHash, boolean active, Instant otpRevealedAt) {
    }
}
