package com.shrestaexclusive.platform.email.provider;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.email.domain.EmailMessage;
import com.shrestaexclusive.platform.email.domain.ProviderOutcome;
import com.shrestaexclusive.platform.email.provider.brevo.BrevoEmailProvider;
import com.shrestaexclusive.platform.email.provider.brevo.BrevoProperties;
import com.shrestaexclusive.platform.email.provider.elasticemail.ElasticEmailProperties;
import com.shrestaexclusive.platform.email.provider.elasticemail.ElasticEmailProvider;
import com.shrestaexclusive.platform.email.provider.mailersend.MailerSendEmailProvider;
import com.shrestaexclusive.platform.email.provider.mailersend.MailerSendProperties;
import com.shrestaexclusive.platform.email.provider.mailjet.MailjetEmailProvider;
import com.shrestaexclusive.platform.email.provider.mailjet.MailjetProperties;
import com.shrestaexclusive.platform.email.provider.resend.ResendEmailProvider;
import com.shrestaexclusive.platform.email.provider.resend.ResendProperties;

class EmailProviderContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EmailMessage message = new EmailMessage(UUID.randomUUID(), "notification-key", "to@example.com", "from@example.com", "SHRESTA", "Subject", "<p>HTML</p>", "Text");

    @Test void brevoUsesApiKeyAndV3Payload() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoProperties props = new BrevoProperties(); props.setEnabled(true); props.setApiKey("test-key"); props.setEndpoint("https://example.test/send");
        server.expect(requestTo(props.getEndpoint())).andExpect(header("api-key", "test-key"))
            .andExpect(jsonPath("$.htmlContent").value("<p>HTML</p>"))
            .andExpect(jsonPath("$.headers.Idempotency-Key").value("notification-key"))
                .andRespond(withSuccess("{\"messageId\":\"brevo-1\"}", MediaType.APPLICATION_JSON));
        assertThat(new BrevoEmailProvider(props, builder, json).send(message).providerMessageId()).isEqualTo("brevo-1"); server.verify();
    }

    @Test void brevoQuarantinesSuccessfulResponseWithoutAcceptanceId() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoProperties props = new BrevoProperties(); props.setEnabled(true); props.setApiKey("test-key"); props.setEndpoint("https://example.test/send");
        server.expect(requestTo(props.getEndpoint())).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(new BrevoEmailProvider(props, builder, json).send(message).outcome()).isEqualTo(ProviderOutcome.OUTCOME_UNKNOWN); server.verify();
    }

    @Test void resendUsesBearerAndIdempotencyHeader() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendProperties props = new ResendProperties(); props.setEnabled(true); props.setApiKey("test-key"); props.setEndpoint("https://example.test/send");
        server.expect(requestTo(props.getEndpoint())).andExpect(header("Authorization", "Bearer test-key")).andExpect(header("Idempotency-Key", "notification-key"))
                .andRespond(withSuccess("{\"id\":\"resend-1\"}", MediaType.APPLICATION_JSON));
        assertThat(new ResendEmailProvider(props, builder, json).send(message).providerMessageId()).isEqualTo("resend-1"); server.verify();
    }

    @Test void resendPreservesProviderRateLimitGuidance() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendProperties props = new ResendProperties(); props.setEnabled(true); props.setApiKey("test-key"); props.setEndpoint("https://example.test/send");
        server.expect(requestTo(props.getEndpoint())).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "120"));
        var result = new ResendEmailProvider(props, builder, json).send(message);
        assertThat(result.outcome()).isEqualTo(ProviderOutcome.TRANSIENT_FAILURE);
        assertThat(result.retryAfterMillis()).isEqualTo(120_000L);
        server.verify();
    }

    @Test void mailjetUsesBasicAndMessagesPayload() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MailjetProperties props = new MailjetProperties(); props.setEnabled(true); props.setApiKey("api"); props.setSecretKey("secret"); props.setEndpoint("https://example.test/send");
        server.expect(requestTo(props.getEndpoint())).andExpect(header("Authorization", "Basic YXBpOnNlY3JldA==")).andExpect(jsonPath("$.Messages[0].CustomID").value("notification-key"))
                .andRespond(withSuccess("{\"Messages\":[{\"To\":[{\"MessageUUID\":\"mailjet-1\"}]}]}", MediaType.APPLICATION_JSON));
        assertThat(new MailjetEmailProvider(props, builder, json).send(message).providerMessageId()).isEqualTo("mailjet-1"); server.verify();
    }

    @Test void mailerSendReadsMessageIdHeader() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MailerSendProperties props = new MailerSendProperties(); props.setEnabled(true); props.setApiKey("test-key"); props.setEndpoint("https://example.test/send");
        server.expect(requestTo(props.getEndpoint())).andExpect(header("Authorization", "Bearer test-key")).andExpect(jsonPath("$.html").value("<p>HTML</p>"))
                .andRespond(withSuccess().header("x-message-id", "mailersend-1"));
        assertThat(new MailerSendEmailProvider(props, builder, json).send(message).providerMessageId()).isEqualTo("mailersend-1"); server.verify();
    }

    @Test void mailerSendRejectsDocumentedAllSuppressedResponse() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MailerSendProperties props = new MailerSendProperties(); props.setEnabled(true); props.setApiKey("test-key"); props.setEndpoint("https://example.test/send");
        server.expect(requestTo(props.getEndpoint())).andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                .body("{\"warnings\":[{\"type\":\"ALL_SUPPRESSED\"}]}"));
        assertThat(new MailerSendEmailProvider(props, builder, json).send(message).outcome()).isEqualTo(ProviderOutcome.PERMANENT_FAILURE); server.verify();
    }

    @Test void mailerSendQuarantinesUnexplainedAcceptedResponseWithoutMessageId() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MailerSendProperties props = new MailerSendProperties(); props.setEnabled(true); props.setApiKey("test-key"); props.setEndpoint("https://example.test/send");
        server.expect(requestTo(props.getEndpoint())).andRespond(withStatus(HttpStatus.ACCEPTED));
        assertThat(new MailerSendEmailProvider(props, builder, json).send(message).outcome()).isEqualTo(ProviderOutcome.OUTCOME_UNKNOWN); server.verify();
    }

    @Test void mailjetDoesNotAcceptPerMessageErrorInsideHttpSuccess() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MailjetProperties props = new MailjetProperties(); props.setEnabled(true); props.setApiKey("api"); props.setSecretKey("secret"); props.setEndpoint("https://example.test/send");
        server.expect(requestTo(props.getEndpoint())).andRespond(withSuccess("{\"Messages\":[{\"Status\":\"error\",\"Errors\":[{\"StatusCode\":400}]}]}", MediaType.APPLICATION_JSON));
        assertThat(new MailjetEmailProvider(props, builder, json).send(message).outcome()).isEqualTo(ProviderOutcome.PERMANENT_FAILURE); server.verify();
    }

    @Test void mailjetQuarantinesMalformedSuccessfulResponse() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MailjetProperties props = new MailjetProperties(); props.setEnabled(true); props.setApiKey("api"); props.setSecretKey("secret"); props.setEndpoint("https://example.test/send");
        server.expect(requestTo(props.getEndpoint())).andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));
        assertThat(new MailjetEmailProvider(props, builder, json).send(message).outcome()).isEqualTo(ProviderOutcome.OUTCOME_UNKNOWN); server.verify();
    }

    @Test void elasticEmailUsesApiKeyAndTransactionalPayload() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ElasticEmailProperties props = new ElasticEmailProperties(); props.setEnabled(true); props.setApiKey("test-key"); props.setEndpoint("https://example.test/send");
            server.expect(requestTo(props.getEndpoint())).andExpect(header("X-ElasticEmail-ApiKey", "test-key")).andExpect(jsonPath("$.Recipients.To[0]").value("to@example.com"))
                .andRespond(withSuccess("{\"TransactionID\":\"transaction-1\",\"MessageID\":\"elastic-1\"}", MediaType.APPLICATION_JSON));
        assertThat(new ElasticEmailProvider(props, builder, json).send(message).providerMessageId()).isEqualTo("elastic-1"); server.verify();
    }

    @Test void elasticEmailQuarantinesSuccessfulResponseWithoutAcceptanceId() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ElasticEmailProperties props = new ElasticEmailProperties(); props.setEnabled(true); props.setApiKey("test-key"); props.setEndpoint("https://example.test/send");
        server.expect(requestTo(props.getEndpoint())).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(new ElasticEmailProvider(props, builder, json).send(message).outcome()).isEqualTo(ProviderOutcome.OUTCOME_UNKNOWN); server.verify();
    }

    @Test void elasticEmailQuarantinesTransactionOnlyResponseWithoutMessageId() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ElasticEmailProperties props = new ElasticEmailProperties(); props.setEnabled(true); props.setApiKey("test-key"); props.setEndpoint("https://example.test/send");
        server.expect(requestTo(props.getEndpoint())).andRespond(withSuccess("{\"TransactionID\":\"transaction-1\"}", MediaType.APPLICATION_JSON));
        assertThat(new ElasticEmailProvider(props, builder, json).send(message).outcome()).isEqualTo(ProviderOutcome.OUTCOME_UNKNOWN); server.verify();
    }

    @Test void classifiesTransientPermanentAndUnknownOutcomes() {
        assertThat(ProviderResponses.http(429, null, 1).outcome()).isEqualTo(ProviderOutcome.TRANSIENT_FAILURE);
        assertThat(ProviderResponses.http(400, null, 1).outcome()).isEqualTo(ProviderOutcome.PERMANENT_FAILURE);
        assertThat(ProviderResponses.http(202, null, 1).outcome()).isEqualTo(ProviderOutcome.OUTCOME_UNKNOWN);
        assertThat(ProviderResponses.http(401, null, 1).outcome()).isEqualTo(ProviderOutcome.CONFIGURATION_FAILURE);
        assertThat(ProviderResponses.unknown(1).outcome()).isEqualTo(ProviderOutcome.OUTCOME_UNKNOWN);
    }

    @Test void retriesKnownConnectionFailuresButQuarantinesAmbiguousReadTimeouts() {
        RuntimeException wrappedConnectFailure = new RuntimeException(new ConnectException("refused"));

        assertThat(ProviderResponses.transport(wrappedConnectFailure, 1).outcome())
                .isEqualTo(ProviderOutcome.TRANSIENT_FAILURE);
        assertThat(ProviderResponses.transport(new HttpTimeoutException("read timed out"), 1).outcome())
                .isEqualTo(ProviderOutcome.OUTCOME_UNKNOWN);
    }

    @Test void parsesRetryAfterSecondsAndHttpDate() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(ProviderResponses.retryAfterMillis("120", now)).isEqualTo(120_000L);
        assertThat(ProviderResponses.retryAfterMillis("Thu, 1 Jan 2026 00:02:00 GMT", now)).isEqualTo(120_000L);
        assertThat(ProviderResponses.retryAfterMillis("-1", now)).isZero();
        assertThat(ProviderResponses.retryAfterMillis("invalid", now)).isZero();
    }
}