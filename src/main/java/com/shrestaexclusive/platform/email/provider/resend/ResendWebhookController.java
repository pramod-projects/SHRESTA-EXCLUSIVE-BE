package com.shrestaexclusive.platform.email.provider.resend;

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

@RestController @RequestMapping("/api/v1/webhooks/email/resend")
public class ResendWebhookController {
    private final ResendProperties properties; private final EmailWebhookService service; private final ObjectMapper json;
    public ResendWebhookController(ResendProperties properties, EmailWebhookService service, ObjectMapper json) { this.properties = properties; this.service = service; this.json = json; }
    @PostMapping public ResponseEntity<Void> receive(@RequestHeader("svix-id") String id, @RequestHeader("svix-timestamp") String timestamp,
            @RequestHeader("svix-signature") String signature, @RequestBody String body) {
        if (!EmailWebhookSupport.svix(properties.getWebhookSecret(), id, timestamp, body, signature)) return ResponseEntity.status(403).build();
        var node = EmailWebhookSupport.json(json, body); var event = EmailWebhookSupport.event(node.path("type").asText());
        if (event.isEmpty()) return ResponseEntity.accepted().build();
        String messageId = EmailWebhookSupport.requiredMessageId(node.path("data").path("email_id").asText(null));
        Instant receivedAt = Instant.now();
        service.accept("RESEND", id, messageId, event.get(),
            EmailWebhookSupport.isoInstant(node.path("created_at"), receivedAt), body);
        return ResponseEntity.accepted().build();
    }
}