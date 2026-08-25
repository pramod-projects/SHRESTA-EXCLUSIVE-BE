package com.shrestaexclusive.platform.email.provider;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import com.shrestaexclusive.platform.email.domain.EmailProviderResult;
import com.shrestaexclusive.platform.email.domain.ProviderOutcome;

public final class ProviderResponses {
    private ProviderResponses() {}

    public static EmailProviderResult http(int status, String messageId, long latencyMs) {
        return http(status, messageId, latencyMs, null);
    }

    public static EmailProviderResult http(int status, String messageId, long latencyMs, String retryAfter) {
        if (status >= 200 && status < 300) {
            if (messageId == null || messageId.isBlank()) {
                return new EmailProviderResult(ProviderOutcome.OUTCOME_UNKNOWN, null, status,
                        "PROVIDER_ACCEPTANCE_ID_MISSING", latencyMs);
            }
            return EmailProviderResult.accepted(messageId, status, latencyMs);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return new EmailProviderResult(ProviderOutcome.TRANSIENT_FAILURE, null, status, "PROVIDER_TRANSIENT",
                    latencyMs, retryAfterMillis(retryAfter, Instant.now()));
        }
        if (status == 401 || status == 403) {
            return new EmailProviderResult(ProviderOutcome.CONFIGURATION_FAILURE, null, status, "PROVIDER_AUTH", latencyMs);
        }
        return new EmailProviderResult(ProviderOutcome.PERMANENT_FAILURE, null, status, "PROVIDER_REJECTED", latencyMs);
    }

    public static EmailProviderResult unknown(long latencyMs) {
        return new EmailProviderResult(ProviderOutcome.OUTCOME_UNKNOWN, null, null, "NETWORK_OUTCOME_UNKNOWN", latencyMs);
    }

    public static EmailProviderResult transport(Throwable failure, long latencyMs) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
                    || cause instanceof UnknownHostException) {
                return new EmailProviderResult(ProviderOutcome.TRANSIENT_FAILURE, null, null,
                        "PROVIDER_CONNECTION_FAILURE", latencyMs);
            }
        }
        return unknown(latencyMs);
    }

    static long retryAfterMillis(String value, Instant now) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Math.max(0, Math.multiplyExact(Long.parseLong(value.trim()), 1_000L));
        } catch (ArithmeticException | NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0, retryAt.toEpochMilli() - now.toEpochMilli());
            } catch (DateTimeParseException invalidDate) {
                return 0L;
            }
        }
    }
}