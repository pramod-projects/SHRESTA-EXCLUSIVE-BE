package com.shrestaexclusive.platform.sms.provider.springedge;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shrestaexclusive.platform.sms.CustomerSmsProperties;
import com.shrestaexclusive.platform.sms.provider.SmsProviderResult;

class SpringEdgeSmsProviderClientTest {

    @Test
    void sendsJsonV1RequestWhenConfigured() throws Exception {
        CustomerSmsProperties properties = baseProperties();
        properties.setSpringEdgeApiMode("json-v1");
        properties.setSpringEdgeEndpoint("https://api.springedge.com/v1/sms/send");

        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"message_id\":\"msg_123\"}");
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(response);

        SpringEdgeSmsProviderClient client = new SpringEdgeSmsProviderClient(properties, httpClient);
        SmsProviderResult result = client.send("9876543210", "Your SHRESTA verification OTP is 123456.");

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("msg_123");
        assertThat(result.requestPayload()).contains("\"to\":\"+919876543210\"");
        assertThat(result.requestPayload()).contains("\"sender_id\":\"SHRSTA\"");
        assertThat(result.requestPayload()).contains("\"type\":\"transactional\"");
    }

    @Test
    void sendsLegacyFormWhenConfigured() throws Exception {
        CustomerSmsProperties properties = baseProperties();
        properties.setSpringEdgeApiMode("legacy-form");
        properties.setSpringEdgeEndpoint("https://instantalerts.co/api/web/send");

        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"id\":\"legacy_1\"}");
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(response);

        SpringEdgeSmsProviderClient client = new SpringEdgeSmsProviderClient(properties, httpClient);
        SmsProviderResult result = client.send("9876543210", "OTP 999999");

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("legacy_1");
        assertThat(result.requestPayload()).contains("apikey=");
        assertThat(result.requestPayload()).contains("to=919876543210");
        assertThat(result.requestPayload()).contains("format=json");
    }

    @Test
    void returnsIncompleteWhenApiKeyMissing() {
        CustomerSmsProperties properties = baseProperties();
        properties.setSpringEdgeApiKey(" ");

        SpringEdgeSmsProviderClient client = new SpringEdgeSmsProviderClient(properties, HttpClient.newHttpClient());
        SmsProviderResult result = client.send("9876543210", "hello");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("SpringEdge configuration is incomplete.");
        assertThat(result.httpStatus()).isNull();
    }

    @Test
    void surfacesRequestFailures() throws Exception {
        CustomerSmsProperties properties = baseProperties();

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new IOException("timeout"));

        SpringEdgeSmsProviderClient client = new SpringEdgeSmsProviderClient(properties, httpClient);
        SmsProviderResult result = client.send("9876543210", "hello");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("SpringEdge request failed");
    }

    private static CustomerSmsProperties baseProperties() {
        CustomerSmsProperties properties = new CustomerSmsProperties();
        properties.setSpringEdgeApiKey("springedge-key");
        properties.setSpringEdgeSender("SHRSTA");
        properties.setSpringEdgeRoute("transactional");
        properties.setSpringEdgeCountryCode("91");
        properties.setSpringEdgeEndpoint("https://api.springedge.com/v1/sms/send");
        return properties;
    }
}
