package com.shrestaexclusive.platform.email.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.email.webhook.EmailWebhookService.NormalizedEmailEvent;

public final class EmailWebhookSupport {
    private static final Duration MAX_PROVIDER_CLOCK_SKEW = Duration.ofMinutes(5);

    private EmailWebhookSupport() {}
    public static boolean constantEquals(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
    public static boolean hmacHex(String secret, String body, String signature) {
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) return false;
        return constantEquals(hexHmac(secret, body), signature.toLowerCase(Locale.ROOT));
    }
    public static boolean svix(String secret, String id, String timestamp, String body, String signature) {
        if (secret == null || secret.isBlank() || id == null || id.isBlank()
                || timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            long seconds = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - seconds) > 300) return false;
            String key = secret.startsWith("whsec_") ? secret.substring(6) : secret;
            String expected = Base64.getEncoder().encodeToString(hmac(Base64.getDecoder().decode(key), id + "." + timestamp + "." + body));
            for (String candidate : signature.split(" ")) if (candidate.equals("v1," + expected)) return true;
            return false;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
    public static JsonNode json(ObjectMapper mapper, String body) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid webhook JSON.", exception);
        }
    }
    public static Optional<NormalizedEmailEvent> event(String raw) {
        String value = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (value.contains("deliver")) return Optional.of(NormalizedEmailEvent.DELIVERED);
        if (value.contains("bounce")) return Optional.of(NormalizedEmailEvent.BOUNCED);
        if (value.contains("complaint") || value.contains("complain") || value.contains("spam")) return Optional.of(NormalizedEmailEvent.COMPLAINT);
        if (value.contains("defer") || value.contains("delay")) return Optional.of(NormalizedEmailEvent.DEFERRED);
        if (value.contains("reject") || value.contains("fail") || value.contains("error")
                || value.contains("block") || value.contains("invalid")) return Optional.of(NormalizedEmailEvent.REJECTED);
        if (value.contains("accept") || value.contains("sent") || value.contains("process")
                || value.contains("submit") || value.contains("queue")) return Optional.of(NormalizedEmailEvent.ACCEPTED);
        return Optional.empty();
    }
    public static String requiredMessageId(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook message identifier is required.");
        }
        return value;
    }
    public static String payloadDigest(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
    public static Instant epochSeconds(JsonNode value, Instant receivedAt) {
        try {
            long seconds = value.asLong(-1);
            return validProviderTime(Instant.ofEpochSecond(seconds), receivedAt);
        } catch (DateTimeException exception) {
            return receivedAt;
        }
    }
    public static Instant isoInstant(JsonNode value, Instant receivedAt) {
        try {
            return validProviderTime(Instant.parse(value.asText()), receivedAt);
        } catch (DateTimeException exception) {
            return receivedAt;
        }
    }
    private static Instant validProviderTime(Instant candidate, Instant receivedAt) {
        return !candidate.isBefore(Instant.EPOCH) && !candidate.isAfter(receivedAt.plus(MAX_PROVIDER_CLOCK_SKEW))
                ? candidate : receivedAt;
    }
    private static String hexHmac(String secret, String body) { return HexFormat.of().formatHex(hmac(secret.getBytes(StandardCharsets.UTF_8), body)); }
    private static byte[] hmac(byte[] key, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", exception);
        }
    }
}