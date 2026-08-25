package com.shrestaexclusive.platform.sms.provider.springedge;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.shrestaexclusive.platform.sms.CustomerSmsProperties;
import com.shrestaexclusive.platform.sms.provider.SmsProviderClient;
import com.shrestaexclusive.platform.sms.provider.SmsProviderResult;

@Component
public class SpringEdgeSmsProviderClient implements SmsProviderClient {

    private final CustomerSmsProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public SpringEdgeSmsProviderClient(CustomerSmsProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
    }

    SpringEdgeSmsProviderClient(CustomerSmsProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public String providerCode() {
        return "SPRINGEDGE";
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(properties.getSpringEdgeApiKey())
                && StringUtils.hasText(properties.getSpringEdgeSender())
                && StringUtils.hasText(properties.getSpringEdgeEndpoint());
    }

    @Override
    public SmsProviderResult send(String mobile, String messageBody) {
        if (!isConfigured()) {
            return new SmsProviderResult(false, null, null, null, null, "SpringEdge configuration is incomplete.");
        }

        return properties.isSpringEdgeJsonMode()
                ? sendJsonV1(mobile, messageBody)
                : sendLegacyForm(mobile, messageBody);
    }

    private SmsProviderResult sendJsonV1(String mobile, String messageBody) {
        String payload = "{" +
                "\"to\":\"" + escapeJson(formatE164(mobile, properties.getSpringEdgeCountryCode())) + "\"," +
                "\"sender_id\":\"" + escapeJson(properties.getSpringEdgeSender()) + "\"," +
                "\"message\":\"" + escapeJson(messageBody) + "\"," +
                "\"type\":\"" + escapeJson(StringUtils.hasText(properties.getSpringEdgeRoute()) ? properties.getSpringEdgeRoute() : "transactional") + "\"" +
                "}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getSpringEdgeEndpoint()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getSpringEdgeApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(10))
                .build();

        return execute(request, payload);
    }

    private SmsProviderResult sendLegacyForm(String mobile, String messageBody) {
        String payload = formEncoded(
                "apikey", properties.getSpringEdgeApiKey(),
                "sender", properties.getSpringEdgeSender(),
                "to", formatNationalWithCountry(mobile, properties.getSpringEdgeCountryCode()),
                "message", messageBody,
                "route", properties.getSpringEdgeRoute(),
                "format", "json"
        );

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getSpringEdgeEndpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(10))
                .build();

        return execute(request, payload);
    }

    private SmsProviderResult execute(HttpRequest request, String payload) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            String responseBody = truncate(response.body(), 4000);
            return new SmsProviderResult(
                    success,
                    extractMessageId(responseBody),
                    response.statusCode(),
                    payload,
                    responseBody,
                    success ? null : "SpringEdge returned HTTP " + response.statusCode()
            );
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new SmsProviderResult(
                    false,
                    null,
                    null,
                    payload,
                    null,
                    truncate("SpringEdge request failed: " + exception.getMessage(), 500)
            );
        }
    }

    private static String escapeJson(String value) {
        String input = value == null ? "" : value;
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String formatNationalWithCountry(String mobile, String countryCode) {
        String country = normalizeCountryCode(countryCode);
        String national = normalizeNationalNumber(mobile, country);
        return country + national;
    }

    private static String formatE164(String mobile, String countryCode) {
        String country = normalizeCountryCode(countryCode);
        String national = normalizeNationalNumber(mobile, country);
        return "+" + country + national;
    }

    private static String normalizeNationalNumber(String mobile, String countryCode) {
        String digits = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (digits.startsWith(countryCode) && digits.length() > countryCode.length()) {
            return digits.substring(countryCode.length());
        }
        return digits;
    }

    private static String normalizeCountryCode(String countryCode) {
        String digits = countryCode == null ? "" : countryCode.replaceAll("\\D", "");
        return StringUtils.hasText(digits) ? digits : "91";
    }

    private static String extractMessageId(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        String messageId = extractJsonField(responseBody, "message_id");
        if (StringUtils.hasText(messageId)) {
            return messageId;
        }
        return extractJsonField(responseBody, "id");
    }

    private static String extractJsonField(String json, String key) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String formEncoded(String... kvPairs) {
        if (kvPairs.length % 2 != 0) {
            throw new IllegalArgumentException("Form payload requires key/value pairs.");
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < kvPairs.length; index += 2) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(kvPairs[index], StandardCharsets.UTF_8));
            builder.append('=');
            builder.append(URLEncoder.encode(kvPairs[index + 1], StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
