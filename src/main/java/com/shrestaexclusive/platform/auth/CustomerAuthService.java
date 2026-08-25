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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.shrestaexclusive.platform.email.application.EmailNotificationCommand;
import com.shrestaexclusive.platform.email.application.EmailNotificationService;
import com.shrestaexclusive.platform.email.domain.NotificationType;
import com.shrestaexclusive.platform.sms.CustomerSmsDeliveryService;

@Service
@SuppressWarnings("exports")
public class CustomerAuthService {

    private static final Logger LOG = LoggerFactory.getLogger(CustomerAuthService.class);

    private static final String STATIC_DEV_LOGIN_OTP = "123456";
    private static final String REGISTRATION_PURPOSE = "LOGIN";
    private static final int REGISTRATION_OTP_TTL_MINUTES = 10;

    private final JdbcClient jdbcClient;
    private final Environment environment;
    private final EmailNotificationService emailNotificationService;
    private final CustomerSmsDeliveryService smsDeliveryService;
    private final RegistrationOtpRateLimiter registrationOtpRateLimiter;
    private final TestUserOtpRateLimiter testUserOtpRateLimiter;
    private final JdbcStorefrontTestAccountRepository testAccountRepository;
    private final TransactionTemplate otpStateTransaction;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public CustomerAuthService(JdbcClient jdbcClient, Environment environment, EmailNotificationService emailNotificationService,
            CustomerSmsDeliveryService smsDeliveryService, RegistrationOtpRateLimiter registrationOtpRateLimiter,
            TestUserOtpRateLimiter testUserOtpRateLimiter, JdbcStorefrontTestAccountRepository testAccountRepository,
            PlatformTransactionManager transactionManager) {
        this(jdbcClient, environment, emailNotificationService, smsDeliveryService, registrationOtpRateLimiter,
            testUserOtpRateLimiter, testAccountRepository, transactionManager, Clock.systemUTC());
    }

    CustomerAuthService(JdbcClient jdbcClient, Environment environment, EmailNotificationService emailNotificationService,
            CustomerSmsDeliveryService smsDeliveryService, RegistrationOtpRateLimiter registrationOtpRateLimiter,
            TestUserOtpRateLimiter testUserOtpRateLimiter, JdbcStorefrontTestAccountRepository testAccountRepository,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.environment = environment;
        this.emailNotificationService = emailNotificationService;
        this.smsDeliveryService = smsDeliveryService;
        this.registrationOtpRateLimiter = registrationOtpRateLimiter;
        this.testUserOtpRateLimiter = testUserOtpRateLimiter;
        this.testAccountRepository = testAccountRepository;
        this.otpStateTransaction = new TransactionTemplate(transactionManager);
        this.otpStateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Transactional
    public CustomerOtpResponse requestOtp(CustomerOtpRequest request) {
        String identity = normalizeIdentity(request.identity());
        String identityType = identity.contains("@") ? "EMAIL" : "MOBILE";
        if (testAccountRepository.isTestAccountIdentity(identity)) {
            return new CustomerOtpResponse("OTP_SENT", maskIdentity(identityType, identity),
                    Instant.now(clock).plus(REGISTRATION_OTP_TTL_MINUTES, ChronoUnit.MINUTES));
        }
        registrationOtpRateLimiter.acquireIdentity(identityType, identity);

        CustomerAccount account = findOrCreateOtpAccount(identityType, identity);
        String otp = newOtpCode();
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(REGISTRATION_OTP_TTL_MINUTES, ChronoUnit.MINUTES);

        expirePendingLoginChallenges(identityType, identity, now);
        insertLoginOtpChallenge(account.customerId(), identityType, identity, hashToken(otp), expiresAt);
        if ("EMAIL".equals(identityType)) {
            emailNotificationService.enqueue(new EmailNotificationCommand(
                    NotificationType.OTP,
                    identity,
                    java.util.Map.of("otp", otp, "expiresMinutes", String.valueOf(REGISTRATION_OTP_TTL_MINUTES)),
                    "customer-login-otp:" + account.customerId() + ":" + expiresAt.toEpochMilli(),
                    account.customerId(),
                    account.customerId().toString()));
        } else {
            smsDeliveryService.sendLoginOtp(account.customerId(), identity, otp);
        }

        return new CustomerOtpResponse("OTP_SENT", maskIdentity(identityType, identity), expiresAt);
    }

    @Transactional
    public CustomerLoginResponse login(CustomerLoginRequest request) {
        String identity = normalizeIdentity(request.identity());
        String otp = request.otp().trim();
        String identityType = identity.contains("@") ? "EMAIL" : "MOBILE";
        CustomerAccount account = findAccountByIdentity(identity)
                .orElseThrow(CustomerLoginFailedException::new);
        if (account.isTest()) {
            return loginWithStaticTestOtp(account, identity, otp);
        }
        if (allowsStaticDevelopmentOtp() && STATIC_DEV_LOGIN_OTP.equals(otp)) {
            activateOtpAccount(account.customerId(), identity, Instant.now(clock));
            account = findAccountByIdentity(identity).orElseThrow(CustomerLoginFailedException::new);
            return createSession(account, "DEV_OTP");
        }
        boolean verified = validateAndConsumeLoginOtpChallenge(identityType, identity, hashToken(otp), Instant.now(clock));
        String authMode = "CUSTOMER_OTP";
        if (!verified) {
            account = findStaticDevelopmentLogin(identity, otp).orElseThrow(CustomerLoginFailedException::new);
            authMode = "DEV_OTP";
        } else {
            activateOtpAccount(account.customerId(), identity, Instant.now(clock));
            account = findAccountByIdentity(identity).orElseThrow(CustomerLoginFailedException::new);
        }

        return createSession(account, authMode);
    }

    private CustomerLoginResponse loginWithStaticTestOtp(CustomerAccount account, String identity, String otp) {
        JdbcStorefrontTestAccountRepository.StorefrontTestAccount testAccount = testAccountRepository
                .findByCustomerId(account.customerId())
                .filter(JdbcStorefrontTestAccountRepository.StorefrontTestAccount::active)
                .orElseThrow(CustomerLoginFailedException::new);
        testUserOtpRateLimiter.acquire(identity);
        if (!MessageDigest.isEqual(hashTokenBytes(otp), HexFormat.of().parseHex(testAccount.otpHash()))) {
            testUserOtpRateLimiter.recordFailure(identity);
            throw new CustomerLoginFailedException();
        }
        testUserOtpRateLimiter.recordSuccess(identity);
        return createSession(account, "TEST_OTP");
    }

    private static byte[] hashTokenBytes(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for customer session hashing", exception);
        }
    }

    private java.util.Optional<CustomerAccount> findStaticDevelopmentLogin(String identity, String otp) {
        if (!allowsStaticDevelopmentOtp()) {
            return java.util.Optional.empty();
        }
        return jdbcClient.sql("""
                        SELECT customer.id, customer.primary_email, customer.display_name, customer.status, customer.is_test
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
                .optional();
            }

            private CustomerLoginResponse createSession(CustomerAccount account, String authMode) {
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
                            CAST(:metadata AS jsonb)
                        )
                        """)
                .param("customerId", account.customerId())
                .param("sessionTokenHash", hashToken(sessionToken))
                .param("issuedAt", Timestamp.from(issuedAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("metadata", "{\"authMode\":\"" + authMode + "\",\"issuer\":\"CustomerAuthService\"}")
                .update();

        return new CustomerLoginResponse(
                account.customerId().toString(),
                account.identityEmail(),
                account.displayName(),
                authMode,
                issuedAt,
                expiresAt,
                sessionToken
        );
    }

    private java.util.Optional<CustomerAccount> findAccountByIdentity(String identity) {
        return jdbcClient.sql("""
                        SELECT customer.id, identity.identity_value AS primary_email,
                               customer.display_name, customer.status, customer.is_test
                        FROM customer_auth_identities identity
                        JOIN customer_accounts customer ON customer.id = identity.customer_id
                        WHERE identity.identity_value = :identity
                          AND customer.status <> 'DELETED'
                        LIMIT 1
                        """)
                .param("identity", identity)
                .query((rs, rowNum) -> mapAccount(rs))
                .optional();
    }

    private CustomerAccount findOrCreateOtpAccount(String identityType, String identity) {
        java.util.Optional<CustomerAccount> existing = findAccountByIdentity(identity);
        if (existing.isPresent()) {
            return existing.get();
        }

        jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtext(:identity))")
                .param("identity", identity)
            .query((rs, rowNum) -> true)
                .single();
        existing = findAccountByIdentity(identity);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID customerId = jdbcClient.sql("""
                        INSERT INTO customer_accounts (primary_email, display_name, status, metadata)
                        VALUES (:identity, 'Shresta Customer', 'SUSPENDED', '{"source":"identity-otp"}'::jsonb)
                        RETURNING id
                        """)
                .param("identity", "EMAIL".equals(identityType) ? identity : null)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .single();
        jdbcClient.sql("""
                        INSERT INTO customer_auth_identities (
                            customer_id, identity_type, identity_value, is_verified, metadata
                        ) VALUES (
                            :customerId, :identityType, :identity, FALSE, '{"source":"identity-otp"}'::jsonb
                        )
                        ON CONFLICT (identity_value) DO NOTHING
                        """)
                .param("customerId", customerId)
                .param("identityType", identityType)
                .param("identity", identity)
                .update();
        return findAccountByIdentity(identity).orElseThrow(CustomerLoginFailedException::new);
    }

    private void expirePendingLoginChallenges(String identityType, String identity, Instant now) {
        jdbcClient.sql("""
                        UPDATE customer_otp_challenges
                        SET status = 'EXPIRED', updated_at = :now
                        WHERE identity_type = :identityType
                          AND identity_value = :identity
                          AND purpose = 'LOGIN'
                          AND status = 'PENDING'
                        """)
                .param("identityType", identityType)
                .param("identity", identity)
                .param("now", Timestamp.from(now))
                .update();
    }

    private void insertLoginOtpChallenge(UUID customerId, String identityType, String identity, String otpHash, Instant expiresAt) {
        jdbcClient.sql("""
                        INSERT INTO customer_otp_challenges (
                            identity_type, identity_value, purpose, otp_hash, status,
                            attempts, max_attempts, expires_at, metadata
                        ) VALUES (
                            :identityType, :identity, 'LOGIN', :otpHash, 'PENDING',
                            0, 5, :expiresAt, CAST(:metadata AS jsonb)
                        )
                        """)
                .param("identityType", identityType)
                .param("identity", identity)
                .param("otpHash", otpHash)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("metadata", "{\"flow\":\"LOGIN_OR_SIGNUP\",\"customerId\":\"" + customerId + "\"}")
                .update();
    }

    private boolean validateAndConsumeLoginOtpChallenge(
            String identityType,
            String identity,
            String expectedOtpHash,
            Instant now
    ) {
        java.util.Optional<OtpChallenge> pending = jdbcClient.sql("""
                        SELECT id, otp_hash, attempts, max_attempts, expires_at
                        FROM customer_otp_challenges
                        WHERE identity_type = :identityType
                          AND identity_value = :identity
                          AND purpose = 'LOGIN'
                          AND status = 'PENDING'
                        ORDER BY created_at DESC
                        LIMIT 1
                        """)
                .param("identityType", identityType)
                .param("identity", identity)
                .query((rs, rowNum) -> new OtpChallenge(
                        rs.getObject("id", UUID.class),
                        rs.getString("otp_hash"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
        if (pending.isEmpty()) {
            return false;
        }

        OtpChallenge challenge = pending.get();
        if (challenge.expiresAt().isBefore(now)) {
            persistExpiredChallenge(challenge.id(), now);
            throw new CustomerLoginFailedException();
        }
        if (!MessageDigest.isEqual(challenge.otpHash().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expectedOtpHash.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            persistFailedAttempt(challenge.id(), now);
            throw new CustomerLoginFailedException();
        }
        int consumed = jdbcClient.sql("""
                        UPDATE customer_otp_challenges
                        SET status = 'VERIFIED', consumed_at = :now, updated_at = :now
                        WHERE id = :id AND status = 'PENDING'
                          AND otp_hash = :otpHash AND expires_at >= :now
                        """)
                .param("now", Timestamp.from(now))
                .param("id", challenge.id())
                .param("otpHash", expectedOtpHash)
                .update();
        if (consumed != 1) {
            throw new CustomerLoginFailedException();
        }
        return true;
    }

    private void activateOtpAccount(UUID customerId, String identity, Instant now) {
        jdbcClient.sql("""
                        UPDATE customer_accounts
                        SET status = 'ACTIVE', updated_at = :now
                        WHERE id = :customerId AND status = 'SUSPENDED'
                        """)
                .param("now", Timestamp.from(now))
                .param("customerId", customerId)
                .update();
        jdbcClient.sql("""
                        UPDATE customer_auth_identities
                        SET is_verified = TRUE, last_verified_at = :now, updated_at = :now
                        WHERE customer_id = :customerId AND identity_value = :identity
                        """)
                .param("now", Timestamp.from(now))
                .param("customerId", customerId)
                .param("identity", identity)
                .update();
    }

    private static String maskIdentity(String identityType, String identity) {
        if ("EMAIL".equals(identityType)) {
            int at = identity.indexOf('@');
            String local = identity.substring(0, at);
            String maskedLocal = local.length() <= 2 ? local.substring(0, 1) + "*" : local.substring(0, 2) + "***";
            return maskedLocal + identity.substring(at);
        }
        return "******" + identity.substring(identity.length() - 4);
    }

    @Transactional
    public CustomerRegistrationResponse register(CustomerRegistrationRequest request) {
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
        registrationOtpRateLimiter.acquire(email, mobile);
        RegistrationCandidate candidate;
        try {
            candidate = findOrCreatePendingRegistration(firstName, middleName, lastName, email, mobile);
        } catch (CustomerRegistrationConflictException conflict) {
            Instant expiresAt = Instant.now(clock).plus(REGISTRATION_OTP_TTL_MINUTES, ChronoUnit.MINUTES);
            LOG.info("registration-otp-request suppressed reason=identity-conflict");
            return new CustomerRegistrationResponse(
                    "OTP_SENT",
                    UUID.randomUUID().toString(),
                    email,
                    mobile,
                    displayNameFromNameParts(firstName, middleName, lastName),
                    null,
                    expiresAt,
                    null
            );
        }
        LOG.info("registration-otp-request candidate customerId={} status={}", candidate.customerId(), candidate.status());
        String registrationOtp = newOtpCode();
        String otpHash = hashToken(registrationOtp);
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(REGISTRATION_OTP_TTL_MINUTES, ChronoUnit.MINUTES);

        expirePendingRegistrationChallenges(email, mobile, now);
        insertOtpChallenge(candidate.customerId(), "EMAIL", email, otpHash, expiresAt);
        insertOtpChallenge(candidate.customerId(), "MOBILE", mobile, otpHash, expiresAt);
        LOG.info("registration-otp-request challenges-created customerId={} expiresAt={}", candidate.customerId(), expiresAt);
        emailNotificationService.enqueue(new EmailNotificationCommand(
                NotificationType.OTP,
                email,
                java.util.Map.of("otp", registrationOtp, "expiresMinutes", String.valueOf(REGISTRATION_OTP_TTL_MINUTES)),
                "registration-otp:" + candidate.customerId() + ":" + expiresAt.toEpochMilli(),
                candidate.customerId(),
                candidate.customerId().toString()));
        try {
            smsDeliveryService.sendRegistrationOtp(candidate.customerId(), mobile, registrationOtp);
        } catch (RuntimeException exception) {
            LOG.warn("registration-otp-sms failed customerId={} reasonClass={}",
                    candidate.customerId(), exception.getClass().getSimpleName());
        }

        return new CustomerRegistrationResponse(
                "OTP_SENT",
                candidate.customerId().toString(),
                email,
                mobile,
                candidate.displayName(),
                null,
                expiresAt,
                exposeDevelopmentCredentials() ? registrationOtp : null
        );
    }

    private CustomerRegistrationResponse verifyRegistrationOtp(String email, String mobile, String otp) {
        RegistrationCandidate candidate = findPendingRegistration(email, mobile)
                .orElseThrow(() -> new CustomerRegistrationVerificationException("Start registration first to receive OTP on email and mobile."));
        LOG.info("registration-otp-verify pending-candidate customerId={} status={}", candidate.customerId(), candidate.status());

        String otpHash = hashToken(otp);
        Instant now = Instant.now(clock);

        validateAndConsumeOtpChallenge("EMAIL", email, otpHash, now);
        validateAndConsumeOtpChallenge("MOBILE", mobile, otpHash, now);

        activateCustomerRegistration(candidate.customerId(), now);
        if (allowsStaticDevelopmentOtp()) {
            upsertSeedLoginOtp(email, STATIC_DEV_LOGIN_OTP);
        }

        return new CustomerRegistrationResponse(
                "VERIFIED",
                candidate.customerId().toString(),
                email,
                mobile,
                candidate.displayName(),
                exposeDevelopmentCredentials() ? STATIC_DEV_LOGIN_OTP : null,
                null,
                null
        );
    }

    boolean exposeDevelopmentCredentials() {
        return environment.acceptsProfiles(Profiles.of("local", "dev"));
    }

    boolean allowsStaticDevelopmentOtp() {
        return environment.acceptsProfiles(Profiles.of("local", "dev"));
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
            LOG.info("registration-candidates count={}", candidates.size());
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
                .orElseThrow(() -> invalidRegistrationOtp());

        if (challenge.expiresAt().isBefore(now)) {
            persistExpiredChallenge(challenge.id(), now);
            throw invalidRegistrationOtp();
        }

        if (!MessageDigest.isEqual(challenge.otpHash().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expectedOtpHash.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            persistFailedAttempt(challenge.id(), now);
            throw invalidRegistrationOtp();
        }

        int consumed = jdbcClient.sql("""
                        UPDATE customer_otp_challenges
                        SET status = 'VERIFIED',
                            consumed_at = :consumedAt,
                            updated_at = :consumedAt
                        WHERE id = :id
                          AND status = 'PENDING'
                          AND otp_hash = :otpHash
                          AND expires_at >= :consumedAt
                        """)
                .param("consumedAt", Timestamp.from(now))
                .param("id", challenge.id())
                .param("otpHash", expectedOtpHash)
                .update();
        if (consumed != 1) {
            throw invalidRegistrationOtp();
        }
    }

    private CustomerRegistrationVerificationException invalidRegistrationOtp() {
        return new CustomerRegistrationVerificationException("Invalid or expired verification code.");
    }

    private void persistFailedAttempt(UUID challengeId, Instant now) {
        otpStateTransaction.executeWithoutResult(status -> jdbcClient.sql("""
                UPDATE customer_otp_challenges
                SET attempts = attempts + 1,
                    status = CASE WHEN attempts + 1 >= max_attempts THEN 'LOCKED' ELSE 'PENDING' END,
                    updated_at = :updatedAt
                WHERE id = :id AND status = 'PENDING'
                """).param("updatedAt", Timestamp.from(now)).param("id", challengeId).update());
    }

    private void persistExpiredChallenge(UUID challengeId, Instant now) {
        otpStateTransaction.executeWithoutResult(status -> jdbcClient.sql("""
                UPDATE customer_otp_challenges
                SET status = 'EXPIRED', updated_at = :updatedAt
                WHERE id = :id AND status = 'PENDING'
                """).param("updatedAt", Timestamp.from(now)).param("id", challengeId).update());
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
                                                             COALESCE(customer.primary_email, (
                                                                     SELECT identity.identity_value
                                                                     FROM customer_auth_identities identity
                                                                     WHERE identity.customer_id = customer.id
                                                                         AND identity.is_verified = TRUE
                                                                     ORDER BY CASE identity.identity_type WHEN 'EMAIL' THEN 0 ELSE 1 END
                                                                     LIMIT 1
                                                             )) AS primary_email,
                               customer.display_name,
                               customer.status,
                               customer.is_test,
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
                rs.getString("status"),
                rs.getBoolean("is_test")
        );
    }

    private static AuthenticatedCustomer mapProfile(ResultSet rs) throws SQLException {
        return new AuthenticatedCustomer(
                rs.getObject("id", UUID.class),
                rs.getString("primary_email"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getBoolean("is_test")
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

    private record CustomerAccount(UUID customerId, String identityEmail, String displayName, String status, boolean isTest) {
    }

    private record RegistrationCandidate(UUID customerId, String identityEmail, String identityMobile, String displayName, String status) {
    }

    private record OtpChallenge(UUID id, String otpHash, int attempts, int maxAttempts, Instant expiresAt) {
    }
}
