package com.shrestaexclusive.platform.sms.provider.springedge;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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

        String payload = formEncoded(
                "apikey", properties.getSpringEdgeApiKey(),
                "sender", properties.getSpringEdgeSender(),
                "to", "91" + mobile,
                "message", messageBody,
                "route", properties.getSpringEdgeRoute(),
                "format", "json"
        );

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getSpringEdgeEndpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            return new SmsProviderResult(
                    success,
                    null,
                    response.statusCode(),
                    payload,
                    truncate(response.body(), 4000),
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
