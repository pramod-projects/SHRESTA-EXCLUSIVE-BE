package com.shrestaexclusive.platform.email.provider.brevo;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.email.domain.EmailMessage;
import com.shrestaexclusive.platform.email.domain.EmailProvider;
import com.shrestaexclusive.platform.email.domain.EmailProviderResult;
import com.shrestaexclusive.platform.email.provider.ProviderResponses;

@Component
public class BrevoEmailProvider implements EmailProvider {
    private final BrevoProperties properties; private final RestClient client; private final ObjectMapper json;
    public BrevoEmailProvider(BrevoProperties properties, RestClient.Builder builder, ObjectMapper json) {
        this.properties = properties; this.client = builder.build(); this.json = json;
    }
    @Override public String code() { return "BREVO"; }
    @Override public boolean enabled() { return properties.isEnabled(); }
    @Override public boolean configured() { return properties.isEnabled() && StringUtils.hasText(properties.getApiKey()); }
    @Override public boolean webhookConfigured() { return StringUtils.hasText(properties.getWebhookSecret()); }
    @Override public EmailProviderResult send(EmailMessage message) {
        long started = System.nanoTime();
        try {
            return client.post().uri(properties.getEndpoint()).header("api-key", properties.getApiKey())
                    .body(request(message)).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        return ProviderResponses.http(status, text(body, "messageId"), elapsed(started),
                            response.getHeaders().getFirst("Retry-After"));
                    });
        } catch (Exception exception) { return ProviderResponses.transport(exception, elapsed(started)); }
    }
    BrevoRequest request(EmailMessage message) {
        return new BrevoRequest(new Contact(message.senderEmail(), message.senderName()), List.of(new Contact(message.recipient(), null)),
                message.subject(), message.htmlBody(), message.textBody(), Map.of("Idempotency-Key", message.idempotencyKey()));
    }
    private String text(String body, String key) {
        try {
            JsonNode value = json.readTree(body).path(key);
            return value.isTextual() ? value.asText() : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }
    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    record Contact(String email, String name) {}
    record BrevoRequest(Contact sender, List<Contact> to, String subject, String htmlContent, String textContent, Map<String, String> headers) {}
}