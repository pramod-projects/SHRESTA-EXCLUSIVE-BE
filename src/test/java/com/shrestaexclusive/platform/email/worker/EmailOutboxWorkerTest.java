package com.shrestaexclusive.platform.email.worker;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shrestaexclusive.platform.email.configuration.EmailProperties;
import com.shrestaexclusive.platform.email.domain.EmailMessage;
import com.shrestaexclusive.platform.email.domain.EmailProviderResult;
import com.shrestaexclusive.platform.email.domain.NotificationType;
import com.shrestaexclusive.platform.email.domain.ProviderAttempt;
import com.shrestaexclusive.platform.email.domain.ProviderOutcome;
import com.shrestaexclusive.platform.email.routing.EmailProviderRouter;
import com.shrestaexclusive.platform.email.template.EmailTemplateRenderer;
import com.shrestaexclusive.platform.email.template.RenderedEmail;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class EmailOutboxWorkerTest {
    private final EmailProperties properties = new EmailProperties();
    private final EmailOutboxRepository repository = mock(EmailOutboxRepository.class);
    private final EmailTemplateRenderer renderer = renderer();
    private final EmailProviderRouter router = mock(EmailProviderRouter.class);
    private final EmailOutboxItem item = new EmailOutboxItem(UUID.randomUUID(), NotificationType.ORDER_CONFIRMATION,
            "customer@example.com", Map.of("orderNumber", "S-1"), "order:S-1", 0,
            Instant.now().plusSeconds(600), Instant.now().minusSeconds(5), "worker", 1);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final EmailOutboxWorker worker = new EmailOutboxWorker(properties, repository, renderer, router, metrics);

    @Test
    void schedulesRetryOnlyForKnownTransientOutcome() {
        EmailProviderResult result = new EmailProviderResult(ProviderOutcome.TRANSIENT_FAILURE, null, 503, "TEMPORARY", 10);
        when(router.send(any(EmailMessage.class))).thenReturn(routed("BREVO", result));
        ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);

        worker.process(item);

        verify(repository).complete(eq(item), any(), retryAt.capture());
        assertThat(retryAt.getValue()).isAfter(Instant.now());
        assertThat(metrics.get("shresta.email.retry").counter().count()).isEqualTo(1);
        assertThat(metrics.get("shresta.email.provider.latency").timer().count()).isEqualTo(1);
        assertThat(metrics.get("shresta.email.queue.age.seconds").summary().count()).isEqualTo(1);
    }

    @Test
    void honorsProviderRetryAfterWhenItFitsBeforeExpiry() {
        EmailProviderResult result = new EmailProviderResult(ProviderOutcome.TRANSIENT_FAILURE, null, 429,
                "RATE_LIMITED", 10, 300_000L);
        when(router.send(any(EmailMessage.class))).thenReturn(routed("BREVO", result));
        ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);

        worker.process(item);

        verify(repository).complete(eq(item), any(), retryAt.capture());
        assertThat(retryAt.getValue()).isAfter(Instant.now().plusSeconds(299));
    }

    @Test
    void doesNotScheduleProviderRetryAfterBeyondMessageExpiry() {
        EmailProviderResult result = new EmailProviderResult(ProviderOutcome.TRANSIENT_FAILURE, null, 429,
                "RATE_LIMITED", 10, Long.MAX_VALUE);
        when(router.send(any(EmailMessage.class))).thenReturn(routed("BREVO", result));

        worker.process(item);

        verify(repository).complete(item, List.of(new ProviderAttempt("BREVO", result)), null);
    }

    @Test
    void quarantinesUnknownOutcomeWithoutRetry() {
        EmailProviderResult result = new EmailProviderResult(ProviderOutcome.OUTCOME_UNKNOWN, null, null, "UNKNOWN", 10);
        when(router.send(any(EmailMessage.class))).thenReturn(routed("BREVO", result));

        worker.process(item);

        verify(repository).complete(item, List.of(new ProviderAttempt("BREVO", result)), null);
    }

    @Test
    void doesNotRetryCompletionWhenLeaseWasLost() {
        EmailProviderResult result = EmailProviderResult.accepted("provider-id", 202, 10);
        when(router.send(any(EmailMessage.class))).thenReturn(routed("RESEND", result));
        doThrow(new IllegalStateException("lease lost")).when(repository).complete(eq(item), any(), any());

        assertThatThrownBy(() -> worker.process(item)).isInstanceOf(IllegalStateException.class).hasMessage("lease lost");
        verify(repository, times(1)).complete(eq(item), any(), any());
    }

    private EmailProviderRouter.RoutedEmailResult routed(String provider, EmailProviderResult result) {
        return new EmailProviderRouter.RoutedEmailResult(provider, result, List.of(new ProviderAttempt(provider, result)));
    }

    private static EmailTemplateRenderer renderer() {
        EmailTemplateRenderer renderer = mock(EmailTemplateRenderer.class);
        when(renderer.render(any(NotificationType.class), any())).thenReturn(new RenderedEmail("Subject", "<p>Body</p>", "Body"));
        return renderer;
    }
}