package com.shrestaexclusive.platform.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shrestaexclusive.platform.notification.CustomerNotificationService;

@Service
public class CustomerAuthService {

    private static final Logger LOG = LoggerFactory.getLogger(CustomerAuthService.class);

    private static final String UAT_DEFAULT_LOGIN_OTP = "123456";
    private static final String REGISTRATION_PURPOSE = "LOGIN";
    private static final int REGISTRATION_OTP_TTL_MINUTES = 10;

    private final JdbcClient jdbcClient;
    private final Environment environment;
    private final CustomerNotificationService notificationService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public CustomerAuthService(JdbcClient jdbcClient, Environment environment, CustomerNotificationService notificationService) {
        this(jdbcClient, environment, notificationService, Clock.systemUTC());
    }

    CustomerAuthService(JdbcClient jdbcClient, Environment environment, CustomerNotificationService notificationService, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.environment = environment;
        this.notificationService = notificationService;
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
                .query((rs, rowNum) -> mapAccount(rs))
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

    @Transactional
    public CustomerRegistrationResponse register(CustomerRegistrationRequest request) {
        if (!environment.acceptsProfiles(Profiles.of("local", "dev", "uat"))) {
            throw new CustomerRegistrationUnavailableException();
        }

        String firstName = normalizeNamePart(request.firstName());
        String middleName = normalizeOptionalNamePart(request.middleName());
        String lastName = normalizeNamePart(request.lastName());
        String email = normalizeEmail(request.email());
        String mobile = normalizeMobile(request.mobile());
        String providedOtp = request.otp() == null ? "" : request.otp().trim();

        if (providedOtp.isBlank()) {
            return requestRegistrationOtp(firstName, middleName, lastName, email, mobile);
        }

        return verifyRegistrationOtp(email, mobile, providedOtp);
    }

    private CustomerRegistrationResponse requestRegistrationOtp(String firstName, String middleName, String lastName, String email, String mobile) {
        RegistrationCandidate candidate = findOrCreatePendingRegistration(firstName, middleName, lastName, email, mobile);
        LOG.info("registration-otp-request candidate customerId={} status={} email={} mobile={}",
            candidate.customerId(), candidate.status(), email, mobile);
        String registrationOtp = newOtpCode();
        String otpHash = hashToken(registrationOtp);
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(REGISTRATION_OTP_TTL_MINUTES, ChronoUnit.MINUTES);

        expirePendingRegistrationChallenges(email, mobile, now);
        insertOtpChallenge(candidate.customerId(), "EMAIL", email, otpHash, expiresAt);
        insertOtpChallenge(candidate.customerId(), "MOBILE", mobile, otpHash, expiresAt);
        LOG.info("registration-otp-request challenges-created customerId={} email={} mobile={} expiresAt={}",
            candidate.customerId(), email, mobile, expiresAt);

        try {
            notificationService.sendRegistrationOtp(candidate.customerId(), email, mobile, registrationOtp);
        } catch (RuntimeException exception) {
            // OTP transport failures must not block challenge creation.
            LOG.warn("registration-otp-request notification failed customerId={} email={} mobile={} reason={}",
                    candidate.customerId(), email, mobile, exception.getMessage());
        }

        return new CustomerRegistrationResponse(
                "OTP_SENT",
                candidate.customerId().toString(),
                email,
                mobile,
                candidate.displayName(),
                null,
                expiresAt,
                registrationOtp
        );
    }

    private CustomerRegistrationResponse verifyRegistrationOtp(String email, String mobile, String otp) {
        RegistrationCandidate candidate = findPendingRegistration(email, mobile)
                .orElseThrow(() -> new CustomerRegistrationVerificationException("Start registration first to receive OTP on email and mobile."));
        LOG.info("registration-otp-verify pending-candidate customerId={} email={} mobile={} status={}",
            candidate.customerId(), email, mobile, candidate.status());

        String otpHash = hashToken(otp);
        Instant now = Instant.now(clock);

        validateAndConsumeOtpChallenge("EMAIL", email, otpHash, now);
        validateAndConsumeOtpChallenge("MOBILE", mobile, otpHash, now);

        activateCustomerRegistration(candidate.customerId(), now);
        upsertSeedLoginOtp(email, UAT_DEFAULT_LOGIN_OTP);

        return new CustomerRegistrationResponse(
                "VERIFIED",
                candidate.customerId().toString(),
                email,
                mobile,
                candidate.displayName(),
                UAT_DEFAULT_LOGIN_OTP,
                null,
                null
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

    private RegistrationCandidate findOrCreatePendingRegistration(String firstName, String middleName, String lastName, String email, String mobile) {
        List<RegistrationCandidate> candidates = registrationCandidatesFor(email, mobile);

        if (candidates.isEmpty()) {
            return createPendingRegistration(firstName, middleName, lastName, email, mobile);
        }

        if (candidates.size() > 1) {
            throw new CustomerRegistrationConflictException("Email and mobile are linked to different customer records.");
        }

        RegistrationCandidate candidate = candidates.get(0);
        if ("ACTIVE".equals(candidate.status())) {
            if (email.equals(candidate.identityEmail())) {
                throw new CustomerRegistrationConflictException("This email is already linked to a customer account.");
            }
            throw new CustomerRegistrationConflictException("This mobile number is already linked to a customer account.");
        }

        if (!"SUSPENDED".equals(candidate.status()) || !mobile.equals(candidate.identityMobile())) {
            throw new CustomerRegistrationConflictException("Registration state is inconsistent for this email/mobile pair.");
        }

        String displayName = displayNameFromNameParts(firstName, middleName, lastName);
        if (!displayName.equals(candidate.displayName())) {
            jdbcClient.sql("""
                            UPDATE customer_accounts
                            SET display_name = :displayName,
                                updated_at = :updatedAt
                            WHERE id = :customerId
                            """)
                    .param("displayName", displayName)
                    .param("updatedAt", Timestamp.from(Instant.now(clock)))
                    .param("customerId", candidate.customerId())
                    .update();
            return new RegistrationCandidate(candidate.customerId(), candidate.identityEmail(), candidate.identityMobile(), displayName, candidate.status());
        }

        return candidate;
    }

    private java.util.Optional<RegistrationCandidate> findPendingRegistration(String email, String mobile) {
        return registrationCandidatesFor(email, mobile).stream()
                .filter(candidate -> "SUSPENDED".equals(candidate.status()))
                .filter(candidate -> email.equals(candidate.identityEmail()))
                .filter(candidate -> mobile.equals(candidate.identityMobile()))
                .findFirst();
    }

    private List<RegistrationCandidate> registrationCandidatesFor(String email, String mobile) {
        List<RegistrationCandidate> candidates = jdbcClient.sql("""
                        SELECT customer.id,
                               customer.primary_email,
                               customer.display_name,
                               customer.status,
                               max(CASE WHEN identity.identity_type = 'MOBILE' THEN identity.identity_value ELSE NULL END) AS mobile_identity
                        FROM customer_accounts customer
                        JOIN customer_auth_identities identity ON identity.customer_id = customer.id
                        WHERE identity.identity_value = :email
                           OR identity.identity_value = :mobile
                        GROUP BY customer.id, customer.primary_email, customer.display_name, customer.status
                        """)
                .param("email", email)
                .param("mobile", mobile)
                .query((rs, rowNum) -> new RegistrationCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("primary_email"),
                        rs.getString("mobile_identity"),
                        rs.getString("display_name"),
                        rs.getString("status")
                ))
                .list();
            LOG.info("registration-candidates email={} mobile={} count={} candidates={}",
                email, mobile, candidates.size(), candidates);
            return candidates;
    }

    private RegistrationCandidate createPendingRegistration(String firstName, String middleName, String lastName, String email, String mobile) {
        String displayName = displayNameFromNameParts(firstName, middleName, lastName);
        UUID customerId = jdbcClient.sql("""
                        INSERT INTO customer_accounts (primary_email, display_name, status, metadata)
                        VALUES (:primaryEmail, :displayName, 'SUSPENDED', CAST(:metadata AS jsonb))
                        RETURNING id
                        """)
                .param("primaryEmail", email)
                .param("displayName", displayName)
                .param("metadata", registrationMetadataJson(firstName, middleName, lastName))
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .single();

        jdbcClient.sql("""
                        INSERT INTO customer_auth_identities (
                            customer_id,
                            identity_type,
                            identity_value,
                            is_verified,
                            metadata
                        )
                        VALUES (
                            :customerId,
                            :identityType,
                            :identityValue,
                            FALSE,
                            '{"source":"self-register-pending"}'::jsonb
                        )
                        """)
                .param("customerId", customerId)
                .param("identityType", "EMAIL")
                .param("identityValue", email)
                .update();

        jdbcClient.sql("""
                        INSERT INTO customer_auth_identities (
                            customer_id,
                            identity_type,
                            identity_value,
                            is_verified,
                            metadata
                        )
                        VALUES (
                            :customerId,
                            :identityType,
                            :identityValue,
                            FALSE,
                            '{"source":"self-register-pending"}'::jsonb
                        )
                """)
                .param("customerId", customerId)
                .param("identityType", "MOBILE")
                .param("identityValue", mobile)
                .update();

        return new RegistrationCandidate(customerId, email, mobile, displayName, "SUSPENDED");
    }

    private void expirePendingRegistrationChallenges(String email, String mobile, Instant now) {
        jdbcClient.sql("""
                        UPDATE customer_otp_challenges
                        SET status = 'EXPIRED',
                            updated_at = :updatedAt
                        WHERE purpose = :purpose
                          AND status = 'PENDING'
                          AND (identity_value = :email OR identity_value = :mobile)
                        """)
                .param("purpose", REGISTRATION_PURPOSE)
                .param("email", email)
                .param("mobile", mobile)
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    private void insertOtpChallenge(UUID customerId, String identityType, String identityValue, String otpHash, Instant expiresAt) {
        String metadata = "{\"flow\":\"REGISTRATION\",\"customerId\":\"" + customerId + "\"}";
        jdbcClient.sql("""
                        INSERT INTO customer_otp_challenges (
                            identity_type,
                            identity_value,
                            purpose,
                            otp_hash,
                            status,
                            attempts,
                            max_attempts,
                            expires_at,
                            metadata
                        )
                        VALUES (
                            :identityType,
                            :identityValue,
                            :purpose,
                            :otpHash,
                            'PENDING',
                            0,
                            5,
                            :expiresAt,
                            CAST(:metadata AS jsonb)
                        )
                        """)
                .param("identityType", identityType)
                .param("identityValue", identityValue)
                .param("purpose", REGISTRATION_PURPOSE)
                .param("otpHash", otpHash)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("metadata", metadata)
                .update();
    }

    private void validateAndConsumeOtpChallenge(String identityType, String identityValue, String expectedOtpHash, Instant now) {
        OtpChallenge challenge = jdbcClient.sql("""
                        SELECT id, otp_hash, attempts, max_attempts, expires_at
                        FROM customer_otp_challenges
                        WHERE identity_type = :identityType
                          AND identity_value = :identityValue
                          AND purpose = :purpose
                          AND status = 'PENDING'
                        ORDER BY created_at DESC
                        LIMIT 1
                        """)
                .param("identityType", identityType)
                .param("identityValue", identityValue)
                .param("purpose", REGISTRATION_PURPOSE)
                .query((rs, rowNum) -> new OtpChallenge(
                        rs.getObject("id", UUID.class),
                        rs.getString("otp_hash"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        rs.getTimestamp("expires_at").toInstant()
                ))
                .optional()
                .orElseThrow(() -> new CustomerRegistrationVerificationException("OTP is invalid or missing for " + identityType + "."));

        if (challenge.expiresAt().isBefore(now)) {
            jdbcClient.sql("""
                            UPDATE customer_otp_challenges
                            SET status = 'EXPIRED',
                                updated_at = :updatedAt
                            WHERE id = :id
                            """)
                    .param("updatedAt", Timestamp.from(now))
                    .param("id", challenge.id())
                    .update();
            throw new CustomerRegistrationVerificationException("OTP expired. Request a new OTP and try again.");
        }

        if (!challenge.otpHash().equals(expectedOtpHash)) {
            int nextAttempts = challenge.attempts() + 1;
            String nextStatus = nextAttempts >= challenge.maxAttempts() ? "LOCKED" : "PENDING";
            jdbcClient.sql("""
                            UPDATE customer_otp_challenges
                            SET attempts = :attempts,
                                status = :status,
                                updated_at = :updatedAt
                            WHERE id = :id
                            """)
                    .param("attempts", nextAttempts)
                    .param("status", nextStatus)
                    .param("updatedAt", Timestamp.from(now))
                    .param("id", challenge.id())
                    .update();
            throw new CustomerRegistrationVerificationException("Invalid OTP. Please re-check and try again.");
        }

        jdbcClient.sql("""
                        UPDATE customer_otp_challenges
                        SET status = 'VERIFIED',
                            consumed_at = :consumedAt,
                            updated_at = :consumedAt
                        WHERE id = :id
                        """)
                .param("consumedAt", Timestamp.from(now))
                .param("id", challenge.id())
                .update();
    }

    private void activateCustomerRegistration(UUID customerId, Instant now) {
        jdbcClient.sql("""
                        UPDATE customer_accounts
                        SET status = 'ACTIVE',
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("updatedAt", Timestamp.from(now))
                .param("id", customerId)
                .update();

        jdbcClient.sql("""
                        UPDATE customer_auth_identities
                        SET is_verified = TRUE,
                            last_verified_at = :verifiedAt,
                            updated_at = :verifiedAt
                        WHERE customer_id = :customerId
                        """)
                .param("verifiedAt", Timestamp.from(now))
                .param("customerId", customerId)
                .update();
    }

    private void upsertSeedLoginOtp(String email, String otp) {
        jdbcClient.sql("""
                        INSERT INTO uat_seed_accounts (
                            identity_email,
                            otp_code,
                            customer_enabled,
                            is_active,
                            description
                        )
                        VALUES (
                            :identityEmail,
                            :otpCode,
                            TRUE,
                            TRUE,
                            :description
                        )
                        ON CONFLICT (identity_email)
                        DO UPDATE SET
                            otp_code = EXCLUDED.otp_code,
                            customer_enabled = TRUE,
                            is_active = TRUE,
                            description = EXCLUDED.description,
                            updated_at = now()
                        """)
                .param("identityEmail", email)
                .param("otpCode", otp)
                .param("description", "Self-registered and OTP-verified account")
                .update();
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
                .query((rs, rowNum) -> mapProfile(rs))
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

    private static String normalizeEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeNamePart(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeOptionalNamePart(String value) {
        if (value == null) {
            return "";
        }
        return normalizeNamePart(value);
    }

    private static String normalizeMobile(String value) {
        String digits = value.trim().replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        return digits;
    }

    private static String displayNameFromNameParts(String firstName, String middleName, String lastName) {
        String merged = String.join(" ", firstName, middleName, lastName).trim().replaceAll("\\s+", " ");
        String[] words = merged.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(word.substring(0, 1).toUpperCase(Locale.ROOT));
            builder.append(word.substring(1).toLowerCase(Locale.ROOT));
        }

        String displayName = builder.toString().trim();
        if (displayName.isBlank()) {
            return "Shresta Customer";
        }
        return displayName.length() > 160 ? displayName.substring(0, 160) : displayName;
    }

    private static String registrationMetadataJson(String firstName, String middleName, String lastName) {
        return "{" +
                "\"source\":\"self-register-pending\"," +
                "\"firstName\":\"" + jsonEscape(firstName) + "\"," +
                "\"middleName\":\"" + jsonEscape(middleName) + "\"," +
                "\"lastName\":\"" + jsonEscape(lastName) + "\"" +
                "}";
    }

    private static String jsonEscape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static CustomerAccount mapAccount(ResultSet rs) throws SQLException {
        return new CustomerAccount(
                rs.getObject("id", UUID.class),
                rs.getString("primary_email"),
                rs.getString("display_name"),
                rs.getString("status")
        );
    }

    private static AuthenticatedCustomer mapProfile(ResultSet rs) throws SQLException {
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

    private String newOtpCode() {
        return String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
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

    private record RegistrationCandidate(UUID customerId, String identityEmail, String identityMobile, String displayName, String status) {
    }

    private record OtpChallenge(UUID id, String otpHash, int attempts, int maxAttempts, Instant expiresAt) {
    }
}
