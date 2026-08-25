package com.shrestaexclusive.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

import com.shrestaexclusive.platform.email.application.EmailNotificationService;
import com.shrestaexclusive.platform.sms.CustomerSmsDeliveryService;

class CustomerAuthServiceTest {

    @Test
    void disablesStaticOtpInUatAndProduction() {
        CustomerAuthService localService = serviceFor(environmentWithProfile("local"));
        CustomerAuthService uatService = serviceFor(environmentWithProfile("uat"));
        CustomerAuthService productionService = serviceFor(environmentWithProfile("prod"));

        assertThat(localService.allowsStaticDevelopmentOtp()).isTrue();
        assertThat(uatService.allowsStaticDevelopmentOtp()).isFalse();
        assertThat(productionService.allowsStaticDevelopmentOtp()).isFalse();
    }

    @Test
    void exposesDevelopmentCredentialsOnlyInLocalOrDev() {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        CustomerAuthService service = new CustomerAuthService(
            mock(JdbcClient.class), environment, mock(EmailNotificationService.class),
            mock(CustomerSmsDeliveryService.class), mock(RegistrationOtpRateLimiter.class),
            mock(TestUserOtpRateLimiter.class), mock(JdbcStorefrontTestAccountRepository.class),
            mock(PlatformTransactionManager.class)
        );

        assertThat(service.exposeDevelopmentCredentials()).isFalse();
    }

    private CustomerAuthService serviceFor(Environment environment) {
        return new CustomerAuthService(
            mock(JdbcClient.class), environment, mock(EmailNotificationService.class),
            mock(CustomerSmsDeliveryService.class), mock(RegistrationOtpRateLimiter.class),
            mock(TestUserOtpRateLimiter.class), mock(JdbcStorefrontTestAccountRepository.class),
            mock(PlatformTransactionManager.class)
        );
    }

    private MockEnvironment environmentWithProfile(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
