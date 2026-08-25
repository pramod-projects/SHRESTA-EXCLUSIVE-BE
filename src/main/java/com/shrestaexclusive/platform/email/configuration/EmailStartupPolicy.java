package com.shrestaexclusive.platform.email.configuration;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.shrestaexclusive.platform.email.domain.EmailProvider;

@Component
public class EmailStartupPolicy implements SmartInitializingSingleton {
    private final EmailProperties properties;
    private final List<EmailProvider> providers;

    public EmailStartupPolicy(EmailProperties properties, List<EmailProvider> providers) {
        this.properties = properties;
        this.providers = providers;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!"PROD".equals(normalizedEnvironment())) return;
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Production transactional email must be enabled.");
        }
        List<String> providerOrder = properties.getProviderOrder();
        Set<String> installedProviders = providers.stream().map(EmailProvider::code)
            .filter(code -> !"CAPTURE".equals(code)).collect(Collectors.toSet());
        if (providerOrder == null || providerOrder.isEmpty()
                || providerOrder.stream().anyMatch(code -> !StringUtils.hasText(code) || !installedProviders.contains(code))
                || providerOrder.stream().distinct().count() != providerOrder.size()) {
            throw new IllegalStateException("Production email provider order must contain unique installed provider codes.");
        }
        List<EmailProvider> enabledProviders = providers.stream().filter(EmailProvider::enabled).toList();
        List<EmailProvider> activeProviders = enabledProviders.stream()
            .filter(provider -> providerOrder.contains(provider.code())).toList();
        if (!StringUtils.hasText(properties.getSenderEmail()) || properties.getSenderEmail().contains("\n")
            || activeProviders.isEmpty()) {
            throw new IllegalStateException("Production email requires a valid sender and at least one configured provider.");
        }
        if (enabledProviders.stream().anyMatch(provider -> !provider.configured())) {
            throw new IllegalStateException("Every enabled production email provider requires complete send credentials.");
        }
        if (enabledProviders.stream().anyMatch(provider -> !provider.webhookConfigured())) {
            throw new IllegalStateException("Every production email provider requires authenticated webhook configuration.");
        }
        if (properties.getMaxAttempts() < 1 || properties.getBatchSize() < 1
                || properties.getLeaseDuration().isZero() || properties.getLeaseDuration().isNegative()
                || properties.getConnectTimeout().isZero() || properties.getConnectTimeout().isNegative()
                || properties.getReadTimeout().isZero() || properties.getReadTimeout().isNegative()
                || properties.getConnectTimeout().compareTo(properties.getLeaseDuration()) >= 0
                || properties.getReadTimeout().compareTo(properties.getLeaseDuration()) >= 0
                || properties.getBaseRetryDelay().isZero() || properties.getBaseRetryDelay().isNegative()
                || properties.getMaxRetryDelay().compareTo(properties.getBaseRetryDelay()) < 0
                || properties.getTerminalRetention().isZero() || properties.getTerminalRetention().isNegative()
                || properties.getWebhookRetention().isZero() || properties.getWebhookRetention().isNegative()
                || properties.getUnknownOutcomeAlertAge().isZero() || properties.getUnknownOutcomeAlertAge().isNegative()
                || properties.getPollDelayMs() <= 0 || properties.getWebhookReconcileDelayMs() <= 0) {
            throw new IllegalStateException("Production email worker and retention settings must use positive, consistent bounds.");
        }
    }

    private String normalizedEnvironment() {
        return properties.getEnvironment() == null ? "DEV" : properties.getEnvironment().trim().toUpperCase(Locale.ROOT);
    }
}