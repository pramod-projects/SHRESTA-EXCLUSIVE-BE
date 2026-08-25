package com.shrestaexclusive.platform.email.provider.mailersend;

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
import com.shrestaexclusive.platform.email.domain.ProviderOutcome;
import com.shrestaexclusive.platform.email.provider.ProviderResponses;

@Component
public class MailerSendEmailProvider implements EmailProvider {
    private final MailerSendProperties properties; private final RestClient client; private final ObjectMapper json;
    public MailerSendEmailProvider(MailerSendProperties properties, RestClient.Builder builder, ObjectMapper json) {
        this.properties = properties; client = builder.build(); this.json = json;
    }
    @Override public String code() { return "MAILERSEND"; }
    @Override public boolean enabled() { return properties.isEnabled(); }
    @Override public boolean configured() { return properties.isEnabled() && StringUtils.hasText(properties.getApiKey()); }
    @Override public boolean webhookConfigured() { return StringUtils.hasText(properties.getWebhookSecret()); }
    @Override public EmailProviderResult send(EmailMessage message) {
        long started = System.nanoTime();
        try { return client.post().uri(properties.getEndpoint()).header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey()).body(request(message))
            .exchange((request, response) -> {
                int status = response.getStatusCode().value();
                String messageId = response.getHeaders().getFirst("x-message-id");
                if (status == 202 && !StringUtils.hasText(messageId)) {
                    String body = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    if (allRecipientsSuppressed(body)) {
                        return new EmailProviderResult(ProviderOutcome.PERMANENT_FAILURE, null, status,
                            "MAILERSEND_ALL_RECIPIENTS_SUPPRESSED", elapsed(started));
                    }
                }
                return ProviderResponses.http(status, messageId, elapsed(started),
                    response.getHeaders().getFirst("Retry-After"));
            });
        } catch (Exception exception) { return ProviderResponses.transport(exception, elapsed(started)); }
    }
    MailerSendRequest request(EmailMessage message) { return new MailerSendRequest(new Contact(message.senderEmail(), message.senderName()),
            List.of(new Contact(message.recipient(), null)), message.subject(), message.textBody(), message.htmlBody()); }
    private boolean allRecipientsSuppressed(String body) {
        try {
            for (var warning : json.readTree(body).path("warnings")) {
                if ("ALL_SUPPRESSED".equals(warning.path("type").asText())) return true;
            }
        } catch (JsonProcessingException ignored) { }
        return false;
    }
    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    record Contact(String email, String name) {}
    record MailerSendRequest(Contact from, List<Contact> to, String subject, String text, String html) {}
}