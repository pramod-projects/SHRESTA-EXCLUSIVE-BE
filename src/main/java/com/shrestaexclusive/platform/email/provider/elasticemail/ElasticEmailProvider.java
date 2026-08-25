package com.shrestaexclusive.platform.email.provider.elasticemail;

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
public class ElasticEmailProvider implements EmailProvider {
    private final ElasticEmailProperties properties; private final RestClient client; private final ObjectMapper json;
    public ElasticEmailProvider(ElasticEmailProperties properties, RestClient.Builder builder, ObjectMapper json) { this.properties = properties; client = builder.build(); this.json = json; }
    @Override public String code() { return "ELASTIC_EMAIL"; }
    @Override public boolean enabled() { return properties.isEnabled(); }
    @Override public boolean configured() { return properties.isEnabled() && StringUtils.hasText(properties.getApiKey()); }
    @Override public boolean webhookConfigured() { return StringUtils.hasText(properties.getWebhookSecret()); }
    @Override public EmailProviderResult send(EmailMessage message) {
        long started = System.nanoTime();
        try { return client.post().uri(properties.getEndpoint()).header("X-ElasticEmail-ApiKey", properties.getApiKey()).body(request(message))
                .exchange((request, response) -> { int status = response.getStatusCode().value(); String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    return ProviderResponses.http(status, id(body), elapsed(started), response.getHeaders().getFirst("Retry-After")); });
        } catch (Exception exception) { return ProviderResponses.transport(exception, elapsed(started)); }
    }
    ElasticRequest request(EmailMessage message) { return new ElasticRequest(new Recipients(List.of(message.recipient())),
            new Content(message.senderName() + " <" + message.senderEmail() + ">", message.subject(), List.of(new Body("HTML", message.htmlBody()), new Body("PlainText", message.textBody())))); }
    private String id(String body) {
        try {
            return json.readTree(body).path("MessageID").asText(null);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }
    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    record Recipients(List<String> To) {}
    record Body(String ContentType, String Content) {}
    record Content(String From, String Subject, List<Body> Body) {}
    record ElasticRequest(Recipients Recipients, Content Content) {}
}