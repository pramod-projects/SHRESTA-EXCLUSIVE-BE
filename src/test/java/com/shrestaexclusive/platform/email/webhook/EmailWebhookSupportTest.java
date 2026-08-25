package com.shrestaexclusive.platform.email.webhook;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class EmailWebhookSupportTest {
    @Test void verifiesCurrentSvixSignatureAndRejectsReplayTimestamp() throws Exception {
        String key = Base64.getEncoder().encodeToString("secret-key".getBytes(StandardCharsets.UTF_8));
        String id = "msg_1"; String timestamp = String.valueOf(Instant.now().getEpochSecond()); String body = "{}";
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(Base64.getDecoder().decode(key), "HmacSHA256"));
        String signature = "v1," + Base64.getEncoder().encodeToString(mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        assertThat(EmailWebhookSupport.svix("whsec_" + key, id, timestamp, body, signature)).isTrue();
        assertThat(EmailWebhookSupport.svix("whsec_" + key, id, "1", body, signature)).isFalse();
    }
    @Test void rejectsWrongSharedSecret() { assertThat(EmailWebhookSupport.constantEquals("expected", "wrong")).isFalse(); }
    @Test void normalizesOnlyDeliveryLifecycleEvents() {
        assertThat(EmailWebhookSupport.event("Error")).contains(EmailWebhookService.NormalizedEmailEvent.REJECTED);
        assertThat(EmailWebhookSupport.event("blocked")).contains(EmailWebhookService.NormalizedEmailEvent.REJECTED);
        assertThat(EmailWebhookSupport.event("email.complained")).contains(EmailWebhookService.NormalizedEmailEvent.COMPLAINT);
        assertThat(EmailWebhookSupport.event("open")).isEmpty();
        assertThat(EmailWebhookSupport.event("click")).isEmpty();
    }
    @Test void rejectsInvalidOrImplausibleProviderTimes() throws Exception {
        var json = new ObjectMapper();
        Instant receivedAt = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(EmailWebhookSupport.epochSeconds(json.readTree("-1"), receivedAt)).isEqualTo(receivedAt);
        assertThat(EmailWebhookSupport.epochSeconds(json.readTree("1893456601"), receivedAt)).isEqualTo(receivedAt);
        assertThat(EmailWebhookSupport.isoInstant(json.readTree("\"not-a-time\""), receivedAt)).isEqualTo(receivedAt);
        assertThat(EmailWebhookSupport.isoInstant(json.readTree("\"2026-01-01T00:05:00Z\""), receivedAt))
                .isEqualTo(Instant.parse("2026-01-01T00:05:00Z"));
    }
}