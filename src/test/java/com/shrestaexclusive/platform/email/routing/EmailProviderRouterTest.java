package com.shrestaexclusive.platform.email.routing;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shrestaexclusive.platform.email.configuration.EmailProperties;
import com.shrestaexclusive.platform.email.domain.EmailMessage;
import com.shrestaexclusive.platform.email.domain.EmailProvider;
import com.shrestaexclusive.platform.email.domain.EmailProviderResult;
import com.shrestaexclusive.platform.email.domain.ProviderOutcome;

class EmailProviderRouterTest {
    private final EmailMessage message = new EmailMessage(UUID.randomUUID(), "key", "to@example.com", "from@example.com", "SHRESTA", "subject", "html", "text");

    @Test void failsOverOnlyAfterKnownTransientFailure() {
        EmailProperties properties = properties("PROD");
        EmailProvider first = provider("BREVO", new EmailProviderResult(ProviderOutcome.TRANSIENT_FAILURE, null, 503, "TEMP", 1));
        EmailProvider second = provider("RESEND", EmailProviderResult.accepted("message-2", 200, 1));
        var result = new EmailProviderRouter(properties, List.of(second, first)).send(message);
        assertThat(result.provider()).isEqualTo("RESEND");
        assertThat(result.result().outcome()).isEqualTo(ProviderOutcome.ACCEPTED);
    }

    @Test void doesNotFailOverAnUnknownOutcome() {
        EmailProperties properties = properties("PROD");
        EmailProvider first = provider("BREVO", new EmailProviderResult(ProviderOutcome.OUTCOME_UNKNOWN, null, null, "UNKNOWN", 1));
        EmailProvider second = provider("RESEND", EmailProviderResult.accepted("duplicate", 200, 1));
        var result = new EmailProviderRouter(properties, List.of(first, second)).send(message);
        assertThat(result.provider()).isEqualTo("BREVO");
        assertThat(result.result().outcome()).isEqualTo(ProviderOutcome.OUTCOME_UNKNOWN);
    }

    @Test void doesNotRouteThroughProviderOmittedFromConfiguredOrder() {
        EmailProperties properties = properties("PROD");
        properties.setProviderOrder(List.of("BREVO"));
        EmailProvider first = provider("BREVO", new EmailProviderResult(ProviderOutcome.TRANSIENT_FAILURE, null, 503, "TEMP", 1));
        EmailProvider omitted = provider("RESEND", EmailProviderResult.accepted("unexpected", 200, 1));

        var result = new EmailProviderRouter(properties, List.of(first, omitted)).send(message);

        assertThat(result.provider()).isEqualTo("BREVO");
        assertThat(result.result().outcome()).isEqualTo(ProviderOutcome.TRANSIENT_FAILURE);
        verify(omitted, never()).send(message);
    }

    private EmailProperties properties(String environment) { EmailProperties value = new EmailProperties(); value.setEnvironment(environment); return value; }
    private EmailProvider provider(String code, EmailProviderResult result) { EmailProvider value = mock(EmailProvider.class); when(value.code()).thenReturn(code); when(value.configured()).thenReturn(true); when(value.send(message)).thenReturn(result); return value; }
}