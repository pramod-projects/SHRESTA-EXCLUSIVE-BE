package com.shrestaexclusive.platform.email.provider.resend;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
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
public class ResendEmailProvider implements EmailProvider {
    private final ResendProperties properties; private final RestClient client; private final ObjectMapper json;
    public ResendEmailProvider(ResendProperties properties, RestClient.Builder builder, ObjectMapper json) { this.properties = properties; client = builder.build(); this.json = json; }
    @Override public String code() { return "RESEND"; }
    @Override public boolean enabled() { return properties.isEnabled(); }
    @Override public boolean configured() { return properties.isEnabled() && StringUtils.hasText(properties.getApiKey()); }
    @Override public boolean webhookConfigured() { return StringUtils.hasText(properties.getWebhookSecret()); }
    @Override public EmailProviderResult send(EmailMessage message) {
        long started = System.nanoTime();
        try { return client.post().uri(properties.getEndpoint()).header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .header("Idempotency-Key", message.idempotencyKey()).body(request(message)).exchange((request, response) -> {
                    int status = response.getStatusCode().value(); String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    return ProviderResponses.http(status, id(body), elapsed(started), response.getHeaders().getFirst("Retry-After"));
                }); } catch (Exception exception) { return ProviderResponses.transport(exception, elapsed(started)); }
    }
    ResendRequest request(EmailMessage message) { return new ResendRequest(message.senderName() + " <" + message.senderEmail() + ">", List.of(message.recipient()), message.subject(), message.htmlBody(), message.textBody()); }
    private String id(String body) {
        try {
            return json.readTree(body).path("id").asText(null);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }
    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    record ResendRequest(String from, List<String> to, String subject, String html, String text) {}
}