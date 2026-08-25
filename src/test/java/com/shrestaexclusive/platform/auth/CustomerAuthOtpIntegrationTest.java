package com.shrestaexclusive.platform.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shrestaexclusive.platform.email.application.EmailNotificationService;
import com.shrestaexclusive.platform.sms.CustomerSmsDeliveryService;

@ActiveProfiles("dev")
@Testcontainers
@SpringBootTest(properties = "shresta.scheduling.enabled=false")
@MockBean(classes = {
    RegistrationOtpRateLimiter.class,
    EmailNotificationService.class,
    CustomerSmsDeliveryService.class
})
class CustomerAuthOtpIntegrationTest {
    @Container
    @ServiceConnection
    @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shresta")
            .withUsername("shresta_app")
            .withPassword("change-me");

    @Autowired private CustomerAuthService service;
    @Autowired private JdbcClient jdbc;
    @Autowired private EmailNotificationService emailNotificationService;
    @Autowired private CustomerSmsDeliveryService smsDeliveryService;
    @Autowired private RegistrationOtpRateLimiter registrationOtpRateLimiter;
    @Autowired private TestUserOtpRateLimiter testUserOtpRateLimiter;
    @Autowired private JdbcStorefrontTestAccountRepository testAccountRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void explicitLoginOtpResendExpiresOldChallengeAndCreatesTenMinuteReplacement() {
        String email = "resend-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

        CustomerOtpResponse first = service.requestOtp(new CustomerOtpRequest(email));
        CustomerOtpResponse replacement = service.requestOtp(new CustomerOtpRequest(email));

        assertThat(first.expiresAt()).isBetween(
            Instant.now().plusSeconds(9 * 60),
            Instant.now().plusSeconds(11 * 60));
        assertThat(replacement.expiresAt()).isBetween(
            Instant.now().plusSeconds(9 * 60),
            Instant.now().plusSeconds(11 * 60));
        assertThat(jdbc.sql("""
            SELECT status
            FROM customer_otp_challenges
            WHERE identity_type = 'EMAIL' AND identity_value = :email AND purpose = 'LOGIN'
            ORDER BY created_at
            """).param("email", email).query(String.class).list())
            .containsExactly("EXPIRED", "PENDING");
        }

        @Test
        void validLoginOtpActivatesAccountAndCannotBeReplayed() throws Exception {
        UUID customerId = UUID.randomUUID();
        String email = "login-" + customerId.toString().substring(0, 8) + "@example.com";
        String otp = "735194";
        jdbc.sql("INSERT INTO customer_accounts (id, primary_email, display_name, status) VALUES (:id, :email, 'OTP Customer', 'SUSPENDED')")
            .param("id", customerId).param("email", email).update();
        jdbc.sql("INSERT INTO customer_auth_identities (customer_id, identity_type, identity_value) VALUES (:id, 'EMAIL', :email)")
            .param("id", customerId).param("email", email).update();
        jdbc.sql("""
            INSERT INTO customer_otp_challenges
                (identity_type, identity_value, purpose, otp_hash, expires_at)
            VALUES ('EMAIL', :email, 'LOGIN', :hash, :expiresAt)
            """).param("email", email).param("hash", sha256(otp))
              .param("expiresAt", Timestamp.from(Instant.now().plusSeconds(600))).update();

        CustomerLoginResponse response = service.login(new CustomerLoginRequest(email, otp));

        assertThat(response.customerId()).isEqualTo(customerId.toString());
        assertThat(response.authMode()).isEqualTo("CUSTOMER_OTP");
        assertThat(jdbc.sql("SELECT status FROM customer_accounts WHERE id = :id")
            .param("id", customerId).query(String.class).single()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> service.login(new CustomerLoginRequest(email, otp)))
            .isInstanceOf(CustomerLoginFailedException.class);
        }

    @Test
    void concurrentValidRegistrationOtpCanOnlyBeConsumedOnce() throws Exception {
        UUID customerId = UUID.randomUUID();
        String suffix = customerId.toString().replace("-", "").substring(0, 12);
        String email = "otp-" + suffix + "@example.com";
        String mobile = "9" + suffix.replaceAll("[a-f]", "1").substring(0, 9);
        String otp = "482615";
        Instant expiresAt = Instant.now().plusSeconds(600);
        jdbc.sql("INSERT INTO customer_accounts (id, primary_email, display_name, status) VALUES (:id, :email, 'Test Customer', 'SUSPENDED')")
                .param("id", customerId).param("email", email).update();
        jdbc.sql("INSERT INTO customer_auth_identities (customer_id, identity_type, identity_value) VALUES (:id, 'EMAIL', :email), (:id, 'MOBILE', :mobile)")
                .param("id", customerId).param("email", email).param("mobile", mobile).update();
        jdbc.sql("""
                INSERT INTO customer_otp_challenges
                    (identity_type, identity_value, purpose, otp_hash, expires_at)
                VALUES ('EMAIL', :email, 'LOGIN', :hash, :expiresAt),
                       ('MOBILE', :mobile, 'LOGIN', :hash, :expiresAt)
                """).param("email", email).param("mobile", mobile).param("hash", sha256(otp))
                  .param("expiresAt", Timestamp.from(expiresAt)).update();
        CustomerRegistrationRequest request = new CustomerRegistrationRequest(
                "Test", "", "Customer", email, mobile, otp);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 2; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        service.register(request);
                        successes.incrementAndGet();
                    } catch (CustomerRegistrationVerificationException exception) {
                        failures.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM customer_accounts WHERE id = :id")
                .param("id", customerId).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT count(*) FROM customer_otp_challenges WHERE identity_value IN (:email, :mobile) AND status = 'VERIFIED'")
                .param("email", email).param("mobile", mobile).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void staticDevOtpLogsInAnyExistingIdentityWithoutChallengeAndIsReplayable() {
        UUID customerId = UUID.randomUUID();
        String email = "dev-otp-" + customerId.toString().substring(0, 8) + "@example.com";
        jdbc.sql("INSERT INTO customer_accounts (id, primary_email, display_name, status) VALUES (:id, :email, 'Dev OTP Customer', 'SUSPENDED')")
            .param("id", customerId).param("email", email).update();
        jdbc.sql("INSERT INTO customer_auth_identities (customer_id, identity_type, identity_value) VALUES (:id, 'EMAIL', :email)")
            .param("id", customerId).param("email", email).update();

        CustomerLoginResponse first = service.login(new CustomerLoginRequest(email, "123456"));
        CustomerLoginResponse second = service.login(new CustomerLoginRequest(email, "123456"));

        assertThat(first.customerId()).isEqualTo(customerId.toString());
        assertThat(first.authMode()).isEqualTo("DEV_OTP");
        assertThat(second.authMode()).isEqualTo("DEV_OTP");
        assertThat(second.sessionToken()).isNotEqualTo(first.sessionToken());
        assertThat(jdbc.sql("SELECT status FROM customer_accounts WHERE id = :id")
            .param("id", customerId).query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    void staticDevOtpRejectsAnyOtherOtpForNonSeededAccount() {
        UUID customerId = UUID.randomUUID();
        String email = "dev-otp-" + customerId.toString().substring(0, 8) + "@example.com";
        jdbc.sql("INSERT INTO customer_accounts (id, primary_email, display_name, status) VALUES (:id, :email, 'Dev OTP Customer', 'ACTIVE')")
            .param("id", customerId).param("email", email).update();
        jdbc.sql("INSERT INTO customer_auth_identities (customer_id, identity_type, identity_value) VALUES (:id, 'EMAIL', :email)")
            .param("id", customerId).param("email", email).update();

        assertThatThrownBy(() -> service.login(new CustomerLoginRequest(email, "123457")))
            .isInstanceOf(CustomerLoginFailedException.class);
    }

    @Test
    void staticDevOtpWorksWhileRealChallengePendingAndLeavesItConsumable() throws Exception {
        UUID customerId = UUID.randomUUID();
        String email = "dev-otp-" + customerId.toString().substring(0, 8) + "@example.com";
        String realOtp = "481516";
        jdbc.sql("INSERT INTO customer_accounts (id, primary_email, display_name, status) VALUES (:id, :email, 'Dev OTP Customer', 'SUSPENDED')")
            .param("id", customerId).param("email", email).update();
        jdbc.sql("INSERT INTO customer_auth_identities (customer_id, identity_type, identity_value) VALUES (:id, 'EMAIL', :email)")
            .param("id", customerId).param("email", email).update();
        jdbc.sql("""
                INSERT INTO customer_otp_challenges
                    (identity_type, identity_value, purpose, otp_hash, expires_at)
                VALUES ('EMAIL', :email, 'LOGIN', :hash, :expiresAt)
                """).param("email", email).param("hash", sha256(realOtp))
              .param("expiresAt", Timestamp.from(Instant.now().plusSeconds(600))).update();

        CustomerLoginResponse devLogin = service.login(new CustomerLoginRequest(email, "123456"));
        CustomerLoginResponse realLogin = service.login(new CustomerLoginRequest(email, realOtp));

        assertThat(devLogin.authMode()).isEqualTo("DEV_OTP");
        assertThat(realLogin.authMode()).isEqualTo("CUSTOMER_OTP");
        assertThatThrownBy(() -> service.login(new CustomerLoginRequest(email, realOtp)))
            .isInstanceOf(CustomerLoginFailedException.class);
    }

    @Test
    void staticDevOtpIsRejectedOutsideLocalAndDevProfiles() {
        UUID customerId = UUID.randomUUID();
        String email = "dev-otp-" + customerId.toString().substring(0, 8) + "@example.com";
        jdbc.sql("INSERT INTO customer_accounts (id, primary_email, display_name, status) VALUES (:id, :email, 'Dev OTP Customer', 'ACTIVE')")
            .param("id", customerId).param("email", email).update();
        jdbc.sql("INSERT INTO customer_auth_identities (customer_id, identity_type, identity_value) VALUES (:id, 'EMAIL', :email)")
            .param("id", customerId).param("email", email).update();

        org.springframework.mock.env.MockEnvironment prodEnvironment = new org.springframework.mock.env.MockEnvironment();
        prodEnvironment.setActiveProfiles("prod");
        CustomerAuthService prodService = new CustomerAuthService(jdbc, prodEnvironment,
                emailNotificationService, smsDeliveryService, registrationOtpRateLimiter,
                testUserOtpRateLimiter, testAccountRepository, transactionManager, java.time.Clock.systemUTC());

        assertThatThrownBy(() -> prodService.login(new CustomerLoginRequest(email, "123456")))
            .isInstanceOf(CustomerLoginFailedException.class);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}