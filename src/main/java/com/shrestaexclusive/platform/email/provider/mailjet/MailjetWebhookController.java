package com.shrestaexclusive.platform.email.provider.mailjet;

import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.email.webhook.EmailWebhookService;
import com.shrestaexclusive.platform.email.webhook.EmailWebhookSupport;

@RestController @RequestMapping("/api/v1/webhooks/email/mailjet")
public class MailjetWebhookController {
    private final MailjetProperties properties; private final EmailWebhookService service; private final ObjectMapper json;
    public MailjetWebhookController(MailjetProperties properties, EmailWebhookService service, ObjectMapper json) { this.properties = properties; this.service = service; this.json = json; }
    @PostMapping public ResponseEntity<Void> receive(@RequestHeader(value=HttpHeaders.AUTHORIZATION, required=false) String authorization, @RequestBody String body) {
        if (!StringUtils.hasText(properties.getWebhookUsername()) || !StringUtils.hasText(properties.getWebhookPassword())) return ResponseEntity.status(403).build();
        String expected = "Basic " + java.util.Base64.getEncoder().encodeToString((properties.getWebhookUsername()+":"+properties.getWebhookPassword()).getBytes());
        if (!EmailWebhookSupport.constantEquals(expected, authorization)) return ResponseEntity.status(403).build();
        var node = EmailWebhookSupport.json(json, body); var event = EmailWebhookSupport.event(node.path("event").asText());
        if (event.isEmpty()) return ResponseEntity.ok().build();
        String messageId = EmailWebhookSupport.requiredMessageId(node.path("CustomID").asText(
            node.path("MessageUUID").asText(node.path("MessageID").asText(null))));
        String eventId = node.path("event_id").asText(messageId + ":" + node.path("event").asText() + ":" + node.path("time").asText());
        Instant receivedAt = Instant.now();
        service.accept("MAILJET", eventId, messageId, event.get(),
                EmailWebhookSupport.epochSeconds(node.path("time"), receivedAt), body);
        return ResponseEntity.ok().build();
    }
}