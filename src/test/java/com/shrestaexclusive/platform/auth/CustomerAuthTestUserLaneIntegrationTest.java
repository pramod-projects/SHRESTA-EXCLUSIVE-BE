package com.shrestaexclusive.platform.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    TestUserOtpRateLimiter.class,
    EmailNotificationService.class,
    CustomerSmsDeliveryService.class
})
class CustomerAuthTestUserLaneIntegrationTest {
    @Container
    @ServiceConnection
    @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shresta")
            .withUsername("shresta_app")
            .withPassword("change-me");

    @Autowired private CustomerAuthService service;
    @Autowired private JdbcClient jdbc;
    @Autowired private RegistrationOtpRateLimiter registrationOtpRateLimiter;
    @Autowired private TestUserOtpRateLimiter testUserOtpRateLimiter;
    @Autowired private EmailNotificationService emailNotificationService;
    @Autowired private CustomerSmsDeliveryService smsDeliveryService;

    @Test
    void requestOtpForTestIdentityIsSilentSuccessWithNoSideEffects() throws Exception {
        String email = uniqueEmail("test-otp");
        insertTestAccount(email, sha256("482916"), true);

        CustomerOtpResponse response = service.requestOtp(new CustomerOtpRequest(email));

        assertThat(response.status()).isEqualTo("OTP_SENT");
        assertThat(response.destination()).isEqualTo("te***@example.com");
        assertThat(response.expiresAt()).isBetween(
            Instant.now().plusSeconds(9 * 60),
            Instant.now().plusSeconds(11 * 60));
        assertThat(countRows("customer_otp_challenges", "identity_value", email)).isZero();
        assertThat(countRows("customer_accounts", "primary_email", email)).isEqualTo(1);
        verifyNoInteractions(registrationOtpRateLimiter, testUserOtpRateLimiter,
                emailNotificationService, smsDeliveryService);
    }

    @Test
    void staticTestOtpLoginIssuesTestSession() throws Exception {
        String otp = "583014";
        String email = uniqueEmail("test-login");
        UUID customerId = insertTestAccount(email, sha256(otp), true);

        CustomerLoginResponse response = service.login(new CustomerLoginRequest(email, otp));

        assertThat(response.customerId()).isEqualTo(customerId.toString());
        assertThat(response.authMode()).isEqualTo("TEST_OTP");
        assertThat(jdbc.sql("SELECT metadata->>'authMode' FROM customer_sessions WHERE customer_id = :id")
                .param("id", customerId).query(String.class).single()).isEqualTo("TEST_OTP");
        verify(testUserOtpRateLimiter).acquire(email);
        verify(testUserOtpRateLimiter).recordSuccess(email);
    }

    @Test
    void wrongStaticOtpFailsIndistinguishablyFromNormalLogin() throws Exception {
        String email = uniqueEmail("test-wrong");
        UUID customerId = insertTestAccount(email, sha256("111111"), true);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.login(new CustomerLoginRequest(email, "222222")))
                    .isInstanceOf(CustomerLoginFailedException.class)
                    .hasMessage("Invalid email or OTP.");
        }
        verify(testUserOtpRateLimiter, org.mockito.Mockito.times(3)).recordFailure(email);
        assertThat(countSessions(customerId)).isZero();
        verifyNoInteractions(emailNotificationService, smsDeliveryService);
    }

    @Test
    void inactiveTestAccountFailsInertlyWithoutLaneFallthroughOrNotify() throws Exception {
        String otp = "902457";
        String email = uniqueEmail("test-inactive");
        UUID customerId = insertTestAccount(email, sha256(otp), false);

        assertThatThrownBy(() -> service.login(new CustomerLoginRequest(email, otp)))
                .isInstanceOf(CustomerLoginFailedException.class)
                .hasMessage("Invalid email or OTP.");
        verifyNoInteractions(testUserOtpRateLimiter, emailNotificationService, smsDeliveryService);
        assertThat(countSessions(customerId)).isZero();
    }

    @Test
    void testAccountWithoutStaticRowFailsInertly() {
        String email = uniqueEmail("test-norow");
        UUID customerId = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer_accounts (id, primary_email, display_name, status, is_test) VALUES (:id, :email, 'TEST User', 'ACTIVE', TRUE)")
                .param("id", customerId).param("email", email).update();
        jdbc.sql("INSERT INTO customer_auth_identities (customer_id, identity_type, identity_value) VALUES (:id, 'EMAIL', :email)")
                .param("id", customerId).param("email", email).update();

        assertThatThrownBy(() -> service.login(new CustomerLoginRequest(email, "123456")))
                .isInstanceOf(CustomerLoginFailedException.class);
        verifyNoInteractions(testUserOtpRateLimiter, emailNotificationService, smsDeliveryService);
    }

    @Test
    void staticTestLaneTakesPrecedenceOverDevelopmentOtpFallback() throws Exception {
        String testOtp = "640219";
        String seedOtp = "123456";
        String email = uniqueEmail("test-precedence");
        insertTestAccount(email, sha256(testOtp), true);
        jdbc.sql("""
                INSERT INTO uat_seed_accounts (identity_email, otp_code, customer_enabled, is_active, description)
                VALUES (:email, :otp, TRUE, TRUE, 'precedence probe')
                """).param("email", email).param("otp", seedOtp).update();

        CustomerLoginResponse response = service.login(new CustomerLoginRequest(email, testOtp));
        assertThat(response.authMode()).isEqualTo("TEST_OTP");

        assertThatThrownBy(() -> service.login(new CustomerLoginRequest(email, seedOtp)))
                .isInstanceOf(CustomerLoginFailedException.class);
    }

    @Test
    void lockedTestIdentityRejectsLoginEvenWithCorrectOtp() throws Exception {
        String otp = "317052";
        String email = uniqueEmail("test-locked");
        UUID customerId = insertTestAccount(email, sha256(otp), true);
        doThrow(new CustomerOtpRequestException("Too many failed attempts. Please try again after some time."))
                .when(testUserOtpRateLimiter).acquire(email);

        assertThatThrownBy(() -> service.login(new CustomerLoginRequest(email, otp)))
                .isInstanceOf(CustomerOtpRequestException.class);
        assertThat(countSessions(customerId)).isZero();
    }

    private UUID insertTestAccount(String email, String otpHash, boolean active) {
        UUID customerId = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer_accounts (id, primary_email, display_name, status, is_test) VALUES (:id, :email, 'TEST User', 'ACTIVE', TRUE)")
                .param("id", customerId).param("email", email).update();
        jdbc.sql("INSERT INTO customer_auth_identities (customer_id, identity_type, identity_value) VALUES (:id, 'EMAIL', :email)")
                .param("id", customerId).param("email", email).update();
        jdbc.sql("""
                INSERT INTO storefront_test_accounts (customer_id, otp_hash, created_by_admin, is_active)
                VALUES (:id, :otpHash, 'platform-test-suite', :active)
                """).param("id", customerId).param("otpHash", otpHash).param("active", active).update();
        return customerId;
    }

    private long countRows(String table, String column, String value) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :value")
                .param("value", value).query(Long.class).single();
    }

    private long countSessions(UUID customerId) {
        return jdbc.sql("SELECT count(*) FROM customer_sessions WHERE customer_id = :id")
                .param("id", customerId).query(Long.class).single();
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@example.com";
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
