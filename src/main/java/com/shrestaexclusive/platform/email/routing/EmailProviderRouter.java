package com.shrestaexclusive.platform.email.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.shrestaexclusive.platform.email.configuration.EmailProperties;
import com.shrestaexclusive.platform.email.domain.EmailMessage;
import com.shrestaexclusive.platform.email.domain.EmailProvider;
import com.shrestaexclusive.platform.email.domain.EmailProviderResult;
import com.shrestaexclusive.platform.email.domain.ProviderAttempt;
import com.shrestaexclusive.platform.email.domain.ProviderOutcome;

@Component
public class EmailProviderRouter {
    private final EmailProperties properties;
    private final List<EmailProvider> providers;

    public EmailProviderRouter(EmailProperties properties, List<EmailProvider> providers) {
        this.properties = properties;
        this.providers = providers;
    }

    public RoutedEmailResult send(EmailMessage message) {
        List<EmailProvider> eligible = providers.stream().filter(EmailProvider::configured)
                .filter(provider -> !"CAPTURE".equals(provider.code()) || isDev())
                .filter(provider -> "CAPTURE".equals(provider.code()) || !isDev())
            .filter(provider -> "CAPTURE".equals(provider.code())
                || properties.getProviderOrder().contains(provider.code()))
                .sorted(Comparator.comparingInt(provider -> order(provider.code()))).toList();
        EmailProviderResult last = null;
        String lastProvider = null;
        List<ProviderAttempt> attempts = new ArrayList<>();
        for (EmailProvider provider : eligible) {
            lastProvider = provider.code();
            last = provider.send(message);
            attempts.add(new ProviderAttempt(lastProvider, last));
            if (last.outcome() != ProviderOutcome.TRANSIENT_FAILURE) return new RoutedEmailResult(lastProvider, last, List.copyOf(attempts));
        }
        if (last == null) {
            last = new EmailProviderResult(ProviderOutcome.CONFIGURATION_FAILURE, null, null, "NO_CONFIGURED_PROVIDER", 0);
            attempts.add(new ProviderAttempt("NONE", last));
        }
        return new RoutedEmailResult(lastProvider, last, List.copyOf(attempts));
    }

    private int order(String code) {
        if ("CAPTURE".equals(code)) return -1;
        int index = properties.getProviderOrder().indexOf(code);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private boolean isDev() {
        return "DEV".equals(properties.getEnvironment().trim().toUpperCase(Locale.ROOT));
    }

    public record RoutedEmailResult(String provider, EmailProviderResult result, List<ProviderAttempt> attempts) {}
}