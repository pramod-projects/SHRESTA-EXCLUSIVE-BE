package com.shrestaexclusive.platform.email.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.shrestaexclusive.platform.email.configuration.EmailProperties;

import io.micrometer.core.instrument.MeterRegistry;

@Component
public class EmailOutboxMaintenance {
    private static final Logger LOG = LoggerFactory.getLogger(EmailOutboxMaintenance.class);
    private final EmailProperties properties;
    private final EmailOutboxRepository repository;
    private final MeterRegistry metrics;

    public EmailOutboxMaintenance(EmailProperties properties, EmailOutboxRepository repository, MeterRegistry metrics) {
        this.properties = properties;
        this.repository = repository;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${shresta.email.maintenance-cron:0 17 * * * *}")
    public void maintain() {
        EmailOutboxRepository.MaintenanceResult result = repository.maintain(properties.getTerminalRetention(),
                properties.getWebhookRetention(), properties.getUnknownOutcomeAlertAge());
        metrics.counter("shresta.email.maintenance.expired", "data", "outbox")
            .increment(result.expiredOutboxRows());
        metrics.counter("shresta.email.maintenance.scrubbed", "data", "otp").increment(result.scrubbedOtpRows());
        metrics.counter("shresta.email.maintenance.deleted", "data", "outbox").increment(result.deletedOutboxRows());
        metrics.counter("shresta.email.maintenance.deleted", "data", "webhook").increment(result.deletedWebhookRows());
        if (result.agedUnknownRows() > 0) {
            metrics.counter("shresta.email.outcome_unknown.alert").increment();
            LOG.error("email-outcome-unknown agedCount={} action=manual-reconciliation-required", result.agedUnknownRows());
        }
    }
}