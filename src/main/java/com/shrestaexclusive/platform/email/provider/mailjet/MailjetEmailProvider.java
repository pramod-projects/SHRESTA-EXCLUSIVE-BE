package com.shrestaexclusive.platform.email.provider.mailjet;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.email.domain.EmailMessage;
import com.shrestaexclusive.platform.email.domain.EmailProvider;
import com.shrestaexclusive.platform.email.domain.EmailProviderResult;
import com.shrestaexclusive.platform.email.provider.ProviderResponses;

@Component
public class MailjetEmailProvider implements EmailProvider {
    private final MailjetProperties properties; private final RestClient client; private final ObjectMapper json;
    public MailjetEmailProvider(MailjetProperties properties, RestClient.Builder builder, ObjectMapper json) { this.properties = properties; client = builder.build(); this.json = json; }
    @Override public String code() { return "MAILJET"; }
    @Override public boolean enabled() { return properties.isEnabled(); }
    @Override public boolean configured() { return properties.isEnabled() && StringUtils.hasText(properties.getApiKey()) && StringUtils.hasText(properties.getSecretKey()); }
    @Override public boolean webhookConfigured() {
        return StringUtils.hasText(properties.getWebhookUsername()) && StringUtils.hasText(properties.getWebhookPassword());
    }
    @Override public EmailProviderResult send(EmailMessage message) {
        long started = System.nanoTime();
        try { return client.post().uri(properties.getEndpoint()).headers(headers -> headers.setBasicAuth(properties.getApiKey(), properties.getSecretKey()))
                .body(request(message)).exchange((request, response) -> { int status = response.getStatusCode().value(); String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    return mailjetResult(status, body, elapsed(started), response.getHeaders().getFirst("Retry-After")); });
        } catch (Exception exception) { return ProviderResponses.transport(exception, elapsed(started)); }
    }
    MailjetRequest request(EmailMessage message) { return new MailjetRequest(List.of(new MailjetMessage(new Contact(message.senderEmail(), message.senderName()),
            List.of(new Contact(message.recipient(), null)), message.subject(), message.textBody(), message.htmlBody(), message.idempotencyKey()))); }
    private String id(String body) {
        try {
            return json.readTree(body).path("Messages").path(0).path("To").path(0).path("MessageUUID").asText(null);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }
    private EmailProviderResult mailjetResult(int status, String body, long latencyMs, String retryAfter) {
        if (status >= 200 && status < 300) {
            try {
                if ("error".equalsIgnoreCase(json.readTree(body).path("Messages").path(0).path("Status").asText())) {
                    return new EmailProviderResult(com.shrestaexclusive.platform.email.domain.ProviderOutcome.PERMANENT_FAILURE,
                            null, status, "MAILJET_MESSAGE_REJECTED", latencyMs);
                }
            } catch (JsonProcessingException ignored) {
                return new EmailProviderResult(com.shrestaexclusive.platform.email.domain.ProviderOutcome.OUTCOME_UNKNOWN,
                        null, status, "MAILJET_INVALID_RESPONSE", latencyMs);
            }
        }
        return ProviderResponses.http(status, id(body), latencyMs, retryAfter);
    }
    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    record Contact(String Email, String Name) {}
    record MailjetMessage(Contact From, List<Contact> To, String Subject, String TextPart, String HTMLPart, String CustomID) {}
    record MailjetRequest(List<MailjetMessage> Messages) {}
}