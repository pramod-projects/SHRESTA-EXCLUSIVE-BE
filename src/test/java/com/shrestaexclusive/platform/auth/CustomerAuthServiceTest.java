package com.shrestaexclusive.platform.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.shrestaexclusive.platform.notification.CustomerNotificationService;

class CustomerAuthServiceTest {

    @Test
    void disablesSeedLoginOutsideLocalDevAndUatProfiles() {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        CustomerAuthService service = new CustomerAuthService(
            mock(JdbcClient.class),
            environment,
            mock(CustomerNotificationService.class)
        );

        assertThatThrownBy(() -> service.login(new CustomerLoginRequest("testuser@gmail.com", "123456")))
                .isInstanceOf(CustomerLoginUnavailableException.class);
    }
}
