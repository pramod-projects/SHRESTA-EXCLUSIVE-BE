package com.shrestaexclusive.platform.email.provider.brevo;

import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.email.webhook.EmailWebhookService;
import com.shrestaexclusive.platform.email.webhook.EmailWebhookSupport;

@RestController @RequestMapping("/api/v1/webhooks/email/brevo")
public class BrevoWebhookController {
    private final BrevoProperties properties; private final EmailWebhookService service; private final ObjectMapper json;
    public BrevoWebhookController(BrevoProperties properties, EmailWebhookService service, ObjectMapper json) { this.properties = properties; this.service = service; this.json = json; }
    @PostMapping public ResponseEntity<Void> receive(@RequestHeader(value="X-SHRESTA-WEBHOOK-SECRET", required=false) String secret,
            @RequestParam(value="token", required=false) String token, @RequestBody String body) {
        if (!EmailWebhookSupport.constantEquals(properties.getWebhookSecret(), secret)
                && !EmailWebhookSupport.constantEquals(properties.getWebhookSecret(), token)) return ResponseEntity.status(403).build();
        var node = EmailWebhookSupport.json(json, body); var event = EmailWebhookSupport.event(node.path("event").asText());
        if (event.isEmpty()) return ResponseEntity.accepted().build();
        String messageId = EmailWebhookSupport.requiredMessageId(node.path("message-id").asText(node.path("messageId").asText(null)));
        Instant receivedAt = Instant.now();
        service.accept("BREVO", EmailWebhookSupport.payloadDigest(body), messageId, event.get(),
            EmailWebhookSupport.epochSeconds(node.path("ts_event"), receivedAt), body);
        return ResponseEntity.accepted().build();
    }
}