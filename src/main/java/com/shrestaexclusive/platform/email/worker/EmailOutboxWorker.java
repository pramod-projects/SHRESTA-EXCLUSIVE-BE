package com.shrestaexclusive.platform.email.worker;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.shrestaexclusive.platform.email.configuration.EmailProperties;
import com.shrestaexclusive.platform.email.domain.EmailMessage;
import com.shrestaexclusive.platform.email.domain.EmailProviderResult;
import com.shrestaexclusive.platform.email.domain.ProviderAttempt;
import com.shrestaexclusive.platform.email.domain.ProviderOutcome;
import com.shrestaexclusive.platform.email.routing.EmailProviderRouter;
import com.shrestaexclusive.platform.email.template.EmailTemplateRenderer;
import com.shrestaexclusive.platform.email.template.RenderedEmail;

import io.micrometer.core.instrument.MeterRegistry;

@Component
public class EmailOutboxWorker {
    private static final Logger LOG = LoggerFactory.getLogger(EmailOutboxWorker.class);
    private final EmailProperties properties;
    private final EmailOutboxRepository repository;
    private final EmailTemplateRenderer renderer;
    private final EmailProviderRouter router;
    private final MeterRegistry metrics;
    private final String owner = ManagementFactory.getRuntimeMXBean().getName();

    public EmailOutboxWorker(EmailProperties properties, EmailOutboxRepository repository,
            EmailTemplateRenderer renderer, EmailProviderRouter router, MeterRegistry metrics) {
        this.properties = properties; this.repository = repository; this.renderer = renderer; this.router = router; this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${shresta.email.poll-delay-ms:5000}")
    public void poll() {
        if (!properties.isEnabled()) return;
        for (EmailOutboxItem item : repository.claim(owner, properties.getBatchSize(), properties.getLeaseDuration())) process(item);
    }

    void process(EmailOutboxItem item) {
        metrics.summary("shresta.email.queue.age.seconds", "type", item.type().name())
            .record(Math.max(0, Duration.between(item.createdAt(), Instant.now()).toMillis()) / 1_000.0);
        List<ProviderAttempt> attempts;
        Instant retryAt;
        String provider;
        ProviderOutcome outcome;
        try {
            RenderedEmail rendered = renderer.render(item.type(), item.variables());
            String recipient = safeRecipient(item.recipient());
            EmailMessage message = new EmailMessage(item.id(), item.idempotencyKey(), recipient,
                properties.getSenderEmail(), properties.getSenderName(), rendered.subject(), rendered.html(), rendered.text());
            EmailProviderRouter.RoutedEmailResult routed = router.send(message);
            Instant now = Instant.now();
            long providerRetryDelay = routed.result().retryAfterMillis();
            long retryDelay = backoffMillis(item.attemptCount() + 1);
            if (providerRetryDelay > retryDelay) {
                retryDelay = providerRetryDelay;
            }
            long remainingLifetime = Math.max(0, Duration.between(now, item.expiresAt()).toMillis());
            retryAt = routed.result().outcome() == ProviderOutcome.TRANSIENT_FAILURE
                && item.attemptCount() + 1 < properties.getMaxAttempts() && retryDelay < remainingLifetime
                ? now.plusMillis(retryDelay) : null;
            attempts = routed.attempts();
            provider = routed.provider();
            outcome = routed.result().outcome();
        } catch (RuntimeException exception) {
            EmailProviderResult failure = new EmailProviderResult(ProviderOutcome.PERMANENT_FAILURE, null, null,
                "LOCAL_PROCESSING_FAILURE", 0);
            attempts = List.of(new ProviderAttempt("NONE", failure));
            retryAt = null;
            provider = "NONE";
            outcome = ProviderOutcome.PERMANENT_FAILURE;
            LOG.warn("email-attempt notificationId={} type={} outcome=PERMANENT_FAILURE reasonClass={}",
                item.id(), item.type(), exception.getClass().getSimpleName());
        }
        repository.complete(item, attempts, retryAt);
        attempts.forEach(attempt -> {
            metrics.counter("shresta.email.attempt", "provider", attempt.provider(),
                "outcome", attempt.result().outcome().name()).increment();
            metrics.timer("shresta.email.provider.latency", "provider", attempt.provider(),
                "outcome", attempt.result().outcome().name())
                .record(Duration.ofMillis(Math.max(0, attempt.result().latencyMs())));
        });
        metrics.counter("shresta.email.outcome", "type", item.type().name(), "outcome", outcome.name()).increment();
        if (retryAt != null) metrics.counter("shresta.email.retry", "type", item.type().name()).increment();
        if (attempts.size() > 1) metrics.counter("shresta.email.failover").increment();
        LOG.info("email-attempt notificationId={} type={} provider={} attempt={} outcome={}",
            item.id(), item.type(), provider, item.attemptCount() + 1, outcome);
    }

    private long backoffMillis(int attempt) {
        long base = properties.getBaseRetryDelay().toMillis();
        long bounded = Math.min(properties.getMaxRetryDelay().toMillis(), base * (1L << Math.min(attempt - 1, 16)));
        return ThreadLocalRandom.current().nextLong(Math.max(1, bounded / 2), bounded + 1);
    }

    private String safeRecipient(String recipient) {
        if (!"UAT".equals(environment())) return recipient;
        if (properties.getUatRecipientOverride() != null && !properties.getUatRecipientOverride().isBlank()) return properties.getUatRecipientOverride();
        if (properties.getUatAllowlist().stream().map(String::toLowerCase).noneMatch(recipient.toLowerCase()::equals)) {
            throw new IllegalStateException("UAT email recipient is not allowlisted.");
        }
        return recipient;
    }

    private String environment() { return properties.getEnvironment().trim().toUpperCase(Locale.ROOT); }
}