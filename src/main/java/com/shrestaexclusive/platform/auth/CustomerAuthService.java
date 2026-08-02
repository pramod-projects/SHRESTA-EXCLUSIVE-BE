package com.shrestaexclusive.platform.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class CustomerAuthService {

    private final JdbcClient jdbcClient;
    private final Environment environment;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public CustomerAuthService(JdbcClient jdbcClient, Environment environment) {
        this(jdbcClient, environment, Clock.systemUTC());
    }

    CustomerAuthService(JdbcClient jdbcClient, Environment environment, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.environment = environment;
        this.clock = clock;
    }

    public CustomerLoginResponse login(CustomerLoginRequest request) {
        if (!environment.acceptsProfiles(Profiles.of("local", "dev", "uat"))) {
            throw new CustomerLoginUnavailableException();
        }

        String identity = normalizeIdentity(request.identity());
        String otp = request.otp().trim();
        CustomerAccount account = jdbcClient.sql("""
                        SELECT customer.id, customer.primary_email, customer.display_name, customer.status
                        FROM uat_seed_accounts seed
                        JOIN customer_accounts customer ON customer.primary_email = seed.identity_email
                        JOIN customer_auth_identities identity ON identity.customer_id = customer.id
                        WHERE identity.identity_value = :identity
                          AND seed.otp_code = :otp
                          AND seed.customer_enabled = TRUE
                          AND seed.is_active = TRUE
                          AND identity.is_verified = TRUE
                          AND customer.status = 'ACTIVE'
                        LIMIT 1
                        """)
                .param("identity", identity)
                .param("otp", otp)
                .query(CustomerAuthService::mapAccount)
                .optional()
                .orElseThrow(CustomerLoginFailedException::new);

        Instant issuedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(12, ChronoUnit.HOURS);
        String sessionToken = newSessionToken();
        jdbcClient.sql("""
                        INSERT INTO customer_sessions (
                            customer_id,
                            session_token_hash,
                            status,
                            issued_at,
                            expires_at,
                            last_seen_at,
                            metadata
                        )
                        VALUES (
                            :customerId,
                            :sessionTokenHash,
                            'ACTIVE',
                            :issuedAt,
                            :expiresAt,
                            :issuedAt,
                            '{"authMode":"DEV_UAT_OTP","issuer":"CustomerAuthService"}'::jsonb
                        )
                        """)
                .param("customerId", account.customerId())
                .param("sessionTokenHash", hashToken(sessionToken))
                .param("issuedAt", Timestamp.from(issuedAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();

        return new CustomerLoginResponse(
                account.customerId().toString(),
                account.identityEmail(),
                account.displayName(),
                "DEV_UAT_OTP",
                issuedAt,
                expiresAt,
                sessionToken
        );
    }

    public CustomerProfileResponse profile(String sessionToken) {
        AuthenticatedCustomer profile = authenticatedCustomer(sessionToken);
        jdbcClient.sql("""
                        UPDATE customer_sessions
                        SET last_seen_at = :now,
                            updated_at = :now
                        WHERE session_token_hash = :sessionTokenHash
                        """)
                .param("now", Timestamp.from(Instant.now(clock)))
                .param("sessionTokenHash", hashToken(sessionToken))
                .update();
        return new CustomerProfileResponse(
                profile.customerId().toString(),
                profile.identityEmail(),
                profile.displayName(),
                profile.status(),
                profile.sessionExpiresAt()
        );
    }

    public AuthenticatedCustomer authenticatedCustomer(String sessionToken) {
        return customerForSession(sessionToken);
    }

    public void logout(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new CustomerUnauthorizedException();
        }

        int updated = jdbcClient.sql("""
                        UPDATE customer_sessions
                        SET status = 'REVOKED',
                            updated_at = :now
                        WHERE session_token_hash = :sessionTokenHash
                          AND status = 'ACTIVE'
                        """)
                .param("now", Timestamp.from(Instant.now(clock)))
                .param("sessionTokenHash", hashToken(sessionToken))
                .update();
        if (updated == 0) {
            throw new CustomerUnauthorizedException();
        }
    }

    private AuthenticatedCustomer customerForSession(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new CustomerUnauthorizedException();
        }

        return jdbcClient.sql("""
                        SELECT customer.id,
                               customer.primary_email,
                               customer.display_name,
                               customer.status,
                               session.expires_at
                        FROM customer_sessions session
                        JOIN customer_accounts customer ON customer.id = session.customer_id
                        WHERE session.session_token_hash = :sessionTokenHash
                          AND session.status = 'ACTIVE'
                          AND session.expires_at > :now
                          AND customer.status = 'ACTIVE'
                        LIMIT 1
                        """)
                .param("sessionTokenHash", hashToken(sessionToken))
                .param("now", Timestamp.from(Instant.now(clock)))
                .query(CustomerAuthService::mapProfile)
                .optional()
                .orElseThrow(CustomerUnauthorizedException::new);
    }

    private static String normalizeIdentity(String rawIdentity) {
        String identity = rawIdentity.trim().toLowerCase(Locale.ROOT);
        if (identity.contains("@")) {
            return identity;
        }
        String digits = identity.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        return digits;
    }

    private static CustomerAccount mapAccount(ResultSet rs, int rowNum) throws SQLException {
        return new CustomerAccount(
                rs.getObject("id", UUID.class),
                rs.getString("primary_email"),
                rs.getString("display_name"),
                rs.getString("status")
        );
    }

    private static AuthenticatedCustomer mapProfile(ResultSet rs, int rowNum) throws SQLException {
        return new AuthenticatedCustomer(
                rs.getObject("id", UUID.class),
                rs.getString("primary_email"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant()
        );
    }

    private String newSessionToken() {
        byte[] tokenBytes = new byte[48];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for customer session hashing", exception);
        }
    }

    private record CustomerAccount(UUID customerId, String identityEmail, String displayName, String status) {
    }
}
