package com.shrestaexclusive.platform.sms.provider.msg91;

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
public class Msg91SmsProviderClient implements SmsProviderClient {

    private static final Pattern OTP_PATTERN = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)");

    private final CustomerSmsProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public Msg91SmsProviderClient(CustomerSmsProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
    }

    Msg91SmsProviderClient(CustomerSmsProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public String providerCode() {
        return "MSG91";
    }

    @Override
    public boolean isConfigured() {
        if (!StringUtils.hasText(properties.getMsg91AuthKey())
                || !StringUtils.hasText(properties.getMsg91Endpoint())) {
            return false;
        }

        if (properties.isMsg91FlowMode()) {
            return StringUtils.hasText(properties.getMsg91TemplateId());
        }

        return StringUtils.hasText(properties.getMsg91Sender());
    }

    @Override
    public SmsProviderResult send(String mobile, String messageBody) {
        if (!isConfigured()) {
            return new SmsProviderResult(false, null, null, null, null, "MSG91 configuration is incomplete.");
        }

        return properties.isMsg91FlowMode()
                ? sendFlowV5(mobile, messageBody)
                : sendLegacyForm(mobile, messageBody);
    }

    private SmsProviderResult sendLegacyForm(String mobile, String messageBody) {
        String nationalMobile = normalizeNationalNumber(mobile);
        String countryCode = normalizeCountryCode(properties.getMsg91Country());
        String fullMobile = countryCode + nationalMobile;

        String payload = formEncoded(
                "authkey", properties.getMsg91AuthKey(),
                "route", properties.getMsg91Route(),
                "sender", properties.getMsg91Sender(),
                "mobiles", fullMobile,
                "message", messageBody,
                "country", countryCode
        );

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getMsg91Endpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(10))
                .build();

        return execute(request, payload);
    }

    private SmsProviderResult sendFlowV5(String mobile, String messageBody) {
        String countryCode = normalizeCountryCode(properties.getMsg91Country());
        String fullMobile = countryCode + normalizeNationalNumber(mobile);
        String variableKey = StringUtils.hasText(properties.getMsg91TemplateVariableKey())
                ? properties.getMsg91TemplateVariableKey().trim()
                : "OTP";
        String variableValue = extractOtpOrMessage(messageBody);

        String payload = "{" +
                "\"template_id\":\"" + escapeJson(properties.getMsg91TemplateId()) + "\"," +
                "\"short_url\":\"0\"," +
                "\"recipients\":[{" +
                "\"mobiles\":\"" + escapeJson(fullMobile) + "\"," +
                "\"" + escapeJson(variableKey) + "\":\"" + escapeJson(variableValue) + "\"" +
                "}]" +
                "}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getMsg91Endpoint()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("authkey", properties.getMsg91AuthKey())
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
                    success ? null : "MSG91 returned HTTP " + response.statusCode()
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
                    truncate("MSG91 request failed: " + exception.getMessage(), 500)
            );
        }
    }

    private static String normalizeNationalNumber(String mobile) {
        String digits = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (digits.length() > 10 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        return digits;
    }

    private static String normalizeCountryCode(String country) {
        String digits = country == null ? "" : country.replaceAll("\\D", "");
        return StringUtils.hasText(digits) ? digits : "91";
    }

    private static String extractOtpOrMessage(String messageBody) {
        String fallback = messageBody == null ? "" : messageBody.trim();
        Matcher matcher = OTP_PATTERN.matcher(fallback);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return fallback;
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

    private static String extractMessageId(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        String requestId = extractJsonField(responseBody, "request_id");
        if (StringUtils.hasText(requestId)) {
            return requestId;
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
