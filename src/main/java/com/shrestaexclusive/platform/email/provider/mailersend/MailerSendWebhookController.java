package com.shrestaexclusive.platform.email.provider.mailersend;

import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.email.webhook.EmailWebhookService;
import com.shrestaexclusive.platform.email.webhook.EmailWebhookSupport;

@RestController @RequestMapping("/api/v1/webhooks/email/mailersend")
public class MailerSendWebhookController {
    private final MailerSendProperties properties; private final EmailWebhookService service; private final ObjectMapper json;
    public MailerSendWebhookController(MailerSendProperties properties, EmailWebhookService service, ObjectMapper json) { this.properties = properties; this.service = service; this.json = json; }
    @PostMapping public ResponseEntity<Void> receive(@RequestHeader(value="Signature", required=false) String signature, @RequestBody String body) {
        if (!EmailWebhookSupport.hmacHex(properties.getWebhookSecret(), body, signature)) return ResponseEntity.status(403).build();
        var node = EmailWebhookSupport.json(json, body); var event = EmailWebhookSupport.event(node.path("type").asText());
        if (event.isEmpty()) return ResponseEntity.accepted().build();
        var data = node.path("data");
        String messageId = EmailWebhookSupport.requiredMessageId(data.path("message_id").asText(
            data.path("email").path("message").path("id").asText(data.path("object").path("id").asText(null))));
        String eventId = data.path("id").asText(messageId + ":" + node.path("type").asText());
        Instant receivedAt = Instant.now();
        service.accept("MAILERSEND", eventId, messageId, event.get(),
            EmailWebhookSupport.isoInstant(node.path("created_at"), receivedAt), body);
        return ResponseEntity.accepted().build();
    }
}