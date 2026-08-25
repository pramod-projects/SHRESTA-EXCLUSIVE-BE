package com.shrestaexclusive.platform.email.configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shrestaexclusive.platform.email.domain.EmailProvider;

class EmailPolicyTest {
    @Test void productionFailsWhenTransactionalEmailIsDisabled() {
        EmailProperties properties = new EmailProperties(); properties.setEnvironment("PROD"); properties.setEnabled(false);
        assertThatThrownBy(() -> new EmailStartupPolicy(properties, List.of()).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be enabled");
    }

    @Test void productionFailsWithoutConfiguredProvider() {
        EmailProperties properties = productionProperties("BREVO");
        assertThatThrownBy(() -> new EmailStartupPolicy(properties, List.of()).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void productionAcceptsConfiguredOrderedProvider() {
        EmailProperties properties = productionProperties("BREVO");
        EmailProvider provider = mock(EmailProvider.class); when(provider.code()).thenReturn("BREVO");
        when(provider.enabled()).thenReturn(true); when(provider.configured()).thenReturn(true);
        when(provider.webhookConfigured()).thenReturn(true);
        new EmailStartupPolicy(properties, List.of(provider)).afterSingletonsInstantiated();
    }

    @Test void productionRejectsProviderWithoutAuthenticatedWebhook() {
        EmailProperties properties = productionProperties("BREVO");
        EmailProvider provider = mock(EmailProvider.class); when(provider.code()).thenReturn("BREVO");
        when(provider.enabled()).thenReturn(true); when(provider.configured()).thenReturn(true);
        when(provider.webhookConfigured()).thenReturn(false);

        assertThatThrownBy(() -> new EmailStartupPolicy(properties, List.of(provider)).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("authenticated webhook");
    }

    @Test void productionRejectsEnabledProviderWithIncompleteSendCredentials() {
        EmailProperties properties = productionProperties("BREVO");
        EmailProvider provider = mock(EmailProvider.class); when(provider.code()).thenReturn("BREVO");
        when(provider.enabled()).thenReturn(true); when(provider.configured()).thenReturn(false);

        assertThatThrownBy(() -> new EmailStartupPolicy(properties, List.of(provider)).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("complete send credentials");
    }

    @Test void productionRejectsIncompleteEnabledProviderOutsideRoutingOrder() {
        EmailProperties properties = productionProperties("BREVO");
        EmailProvider ready = mock(EmailProvider.class); when(ready.code()).thenReturn("BREVO");
        when(ready.enabled()).thenReturn(true); when(ready.configured()).thenReturn(true);
        when(ready.webhookConfigured()).thenReturn(true);
        EmailProvider incomplete = mock(EmailProvider.class); when(incomplete.code()).thenReturn("NOT_ROUTED");
        when(incomplete.enabled()).thenReturn(true); when(incomplete.configured()).thenReturn(false);

        assertThatThrownBy(() -> new EmailStartupPolicy(properties, List.of(ready, incomplete)).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("complete send credentials");
    }

    @Test void productionRejectsNonPositiveSchedulerDelays() {
        EmailProperties properties = productionProperties("BREVO");
        properties.setWebhookReconcileDelayMs(0);
        EmailProvider provider = mock(EmailProvider.class); when(provider.code()).thenReturn("BREVO");
        when(provider.enabled()).thenReturn(true); when(provider.configured()).thenReturn(true);
        when(provider.webhookConfigured()).thenReturn(true);

        assertThatThrownBy(() -> new EmailStartupPolicy(properties, List.of(provider)).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("positive, consistent bounds");
    }

    @Test void productionRejectsUnknownProviderInRoutingOrder() {
        EmailProperties properties = new EmailProperties(); properties.setEnvironment("PROD"); properties.setEnabled(true);
        properties.setProviderOrder(List.of("BREVO", "TYPO"));
        EmailProvider provider = readyProvider("BREVO");

        assertThatThrownBy(() -> new EmailStartupPolicy(properties, List.of(provider)).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unique installed provider codes");
    }

    @Test void productionRejectsDuplicateProviderInRoutingOrder() {
        EmailProperties properties = new EmailProperties(); properties.setEnvironment("PROD"); properties.setEnabled(true);
        properties.setProviderOrder(List.of("BREVO", "BREVO"));
        EmailProvider provider = readyProvider("BREVO");

        assertThatThrownBy(() -> new EmailStartupPolicy(properties, List.of(provider)).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unique installed provider codes");
    }

    private EmailProvider readyProvider(String code) {
        EmailProvider provider = mock(EmailProvider.class); when(provider.code()).thenReturn(code);
        when(provider.enabled()).thenReturn(true); when(provider.configured()).thenReturn(true);
        when(provider.webhookConfigured()).thenReturn(true);
        return provider;
    }

    private EmailProperties productionProperties(String... providerOrder) {
        EmailProperties properties = new EmailProperties(); properties.setEnvironment("PROD"); properties.setEnabled(true);
        properties.setProviderOrder(List.of(providerOrder));
        return properties;
    }
}