package com.shrestaexclusive.platform.email.configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.shrestaexclusive.platform.email.domain.EmailProvider;

class EmailHealthIndicatorTest {
    @Test
    void uatDoesNotReportDevCaptureAsConfiguredDeliveryProvider() {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(true);
        properties.setEnvironment("UAT");
        JdbcClient jdbcClient = mock(JdbcClient.class);
        EmailProvider capture = mock(EmailProvider.class);
        when(capture.code()).thenReturn("CAPTURE");
        when(capture.configured()).thenReturn(true);

        var health = new EmailHealthIndicator(properties, jdbcClient, List.of(capture)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("configuredProviders")).isEqualTo(List.of());
        verify(jdbcClient, never()).sql(org.mockito.ArgumentMatchers.anyString());
    }
}