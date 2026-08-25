package com.shrestaexclusive.platform.email.provider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.email.provider.brevo.BrevoProperties;
import com.shrestaexclusive.platform.email.provider.brevo.BrevoWebhookController;
import com.shrestaexclusive.platform.email.provider.elasticemail.ElasticEmailProperties;
import com.shrestaexclusive.platform.email.provider.elasticemail.ElasticEmailWebhookController;
import com.shrestaexclusive.platform.email.provider.mailersend.MailerSendProperties;
import com.shrestaexclusive.platform.email.provider.mailersend.MailerSendWebhookController;
import com.shrestaexclusive.platform.email.provider.mailjet.MailjetProperties;
import com.shrestaexclusive.platform.email.provider.mailjet.MailjetWebhookController;
import com.shrestaexclusive.platform.email.provider.resend.ResendProperties;
import com.shrestaexclusive.platform.email.provider.resend.ResendWebhookController;
import com.shrestaexclusive.platform.email.webhook.EmailWebhookService;
import com.shrestaexclusive.platform.email.webhook.EmailWebhookService.NormalizedEmailEvent;
import org.springframework.web.server.ResponseStatusException;

class EmailWebhookControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EmailWebhookService service = mock(EmailWebhookService.class);

    @Test
    void brevoAcceptsUrlTokenAndMapsDelivery() {
        BrevoProperties properties = new BrevoProperties();
        properties.setWebhookSecret("brevo-secret");
                String body = "{\"id\":\"event-1\",\"message-id\":\"message-1\",\"event\":\"delivered\",\"ts_event\":1700000001}";

        var response = new BrevoWebhookController(properties, service, json).receive(null, "brevo-secret", body);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(service).accept(eq("BREVO"), eq(com.shrestaexclusive.platform.email.webhook.EmailWebhookSupport.payloadDigest(body)), eq("message-1"),
                eq(NormalizedEmailEvent.DELIVERED), eq(Instant.ofEpochSecond(1700000001)), eq(body));
    }

    @Test
    void resendVerifiesSvixAndMapsBounce() throws Exception {
        String key = Base64.getEncoder().encodeToString("resend-secret".getBytes(StandardCharsets.UTF_8));
        ResendProperties properties = new ResendProperties();
        properties.setWebhookSecret("whsec_" + key);
        String id = "event-2";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"type\":\"email.bounced\",\"created_at\":\"2023-11-14T22:13:22Z\",\"data\":{\"email_id\":\"message-2\"}}";
        String signature = "v1," + Base64.getEncoder().encodeToString(hmac(Base64.getDecoder().decode(key),
                id + "." + timestamp + "." + body));

        var response = new ResendWebhookController(properties, service, json)
                .receive(id, timestamp, signature, body);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(service).accept(eq("RESEND"), eq(id), eq("message-2"), eq(NormalizedEmailEvent.BOUNCED),
                eq(Instant.parse("2023-11-14T22:13:22Z")), eq(body));
    }

    @Test
    void mailjetUsesBasicAuthCustomIdAndOkAcknowledgment() {
        MailjetProperties properties = new MailjetProperties();
        properties.setWebhookUsername("webhook-user");
        properties.setWebhookPassword("webhook-password");
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                "webhook-user:webhook-password".getBytes(StandardCharsets.UTF_8));
        String body = "{\"event_id\":\"event-3\",\"CustomID\":\"outbox-key\",\"event\":\"bounce\",\"time\":1700000003}";

        var response = new MailjetWebhookController(properties, service, json).receive(authorization, body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(service).accept(eq("MAILJET"), eq("event-3"), eq("outbox-key"),
                eq(NormalizedEmailEvent.BOUNCED), eq(Instant.ofEpochSecond(1700000003)), eq(body));
    }

    @Test
    void mailerSendVerifiesHmacAndMapsComplaint() throws Exception {
        MailerSendProperties properties = new MailerSendProperties();
        properties.setWebhookSecret("mailer-secret");
        String body = "{\"type\":\"activity.spam_complaint\",\"created_at\":\"2023-11-14T22:13:24.123456Z\",\"data\":{\"id\":\"event-4\",\"message_id\":\"message-4\"}}";
        String signature = HexFormat.of().formatHex(hmac("mailer-secret".getBytes(StandardCharsets.UTF_8), body));

        var response = new MailerSendWebhookController(properties, service, json).receive(signature, body);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(service).accept(eq("MAILERSEND"), eq("event-4"), eq("message-4"),
                eq(NormalizedEmailEvent.COMPLAINT), eq(Instant.parse("2023-11-14T22:13:24.123456Z")), eq(body));
    }

    @Test
    void elasticEmailUsesProviderEventTime() {
        ElasticEmailProperties properties = new ElasticEmailProperties();
        properties.setWebhookSecret("elastic-secret");
        String body = "{\"EventID\":\"event-5\",\"MsgID\":\"message-5\",\"EventType\":\"Sent\",\"EventDate\":\"2023-11-14T22:13:25Z\"}";

        var response = new ElasticEmailWebhookController(properties, service, json)
                .receive(null, "elastic-secret", body);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(service).accept(eq("ELASTIC_EMAIL"), eq("event-5"), eq("message-5"),
                eq(NormalizedEmailEvent.ACCEPTED), eq(Instant.parse("2023-11-14T22:13:25Z")), eq(body));
    }

    @Test
    void elasticEmailRejectsWrongTokenWithoutProcessing() {
        ElasticEmailProperties properties = new ElasticEmailProperties();
        properties.setWebhookSecret("elastic-secret");

        var response = new ElasticEmailWebhookController(properties, service, json)
                .receive(null, "wrong-token", "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(service, never()).accept(any(), any(), any(), any(), any(), any());
    }

        @Test
        void elasticEmailRejectsTransactionOnlyEventWithoutMessageId() {
                ElasticEmailProperties properties = new ElasticEmailProperties();
                properties.setWebhookSecret("elastic-secret");
                String body = "{\"EventID\":\"event-transaction-only\",\"TransactionID\":\"transaction-1\",\"EventType\":\"Sent\",\"EventDate\":\"2023-11-14T22:13:25Z\"}";

                assertThatThrownBy(() -> new ElasticEmailWebhookController(properties, service, json)
                                .receive(null, "elastic-secret", body))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(400));
                verify(service, never()).accept(any(), any(), any(), any(), any(), any());
        }

    @Test
    void mailjetAcknowledgesUnsupportedEngagementEventWithoutChangingDeliveryState() {
        MailjetProperties properties = new MailjetProperties();
        properties.setWebhookUsername("webhook-user");
        properties.setWebhookPassword("webhook-password");
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                "webhook-user:webhook-password".getBytes(StandardCharsets.UTF_8));

        var response = new MailjetWebhookController(properties, service, json)
                .receive(authorization, "{\"event\":\"open\",\"MessageUUID\":\"message-5\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(service, never()).accept(any(), any(), any(), any(), any(), any());
    }

    @Test
    void resendRejectsSupportedEventWithoutMessageIdentifier() throws Exception {
        String key = Base64.getEncoder().encodeToString("resend-secret".getBytes(StandardCharsets.UTF_8));
        ResendProperties properties = new ResendProperties();
        properties.setWebhookSecret("whsec_" + key);
        String id = "event-missing-message";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"type\":\"email.delivered\",\"data\":{}}";
        String signature = "v1," + Base64.getEncoder().encodeToString(hmac(Base64.getDecoder().decode(key),
                id + "." + timestamp + "." + body));

        assertThatThrownBy(() -> new ResendWebhookController(properties, service, json)
                .receive(id, timestamp, signature, body))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode().value())
                        .isEqualTo(400));
        verify(service, never()).accept(any(), any(), any(), any(), any(), any());
    }

    @Test
    void elasticEmailAcknowledgesUnsupportedEngagementEventWithoutChangingDeliveryState() {
        ElasticEmailProperties properties = new ElasticEmailProperties();
        properties.setWebhookSecret("elastic-secret");

        var response = new ElasticEmailWebhookController(properties, service, json)
                .receive(null, "elastic-secret", "{\"EventType\":\"Open\",\"MessageID\":\"message-6\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(service, never()).accept(any(), any(), any(), any(), any(), any());
    }

    private static byte[] hmac(byte[] key, String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
    }
}