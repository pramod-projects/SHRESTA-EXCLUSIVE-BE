package com.shrestaexclusive.platform.sms.provider.msg91;

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

class Msg91SmsProviderClientTest {

    @Test
    void sendsLegacyFormRequestByDefault() throws Exception {
        CustomerSmsProperties properties = baseProperties();
        properties.setMsg91ApiMode("legacy-form");

        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"request_id\":\"req_legacy_1\"}");
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(response);

        Msg91SmsProviderClient client = new Msg91SmsProviderClient(properties, httpClient);
        SmsProviderResult result = client.send("9876543210", "Your SHRESTA verification OTP is 123456.");

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("req_legacy_1");
        assertThat(result.requestPayload()).contains("authkey=");
        assertThat(result.requestPayload()).contains("mobiles=919876543210");
        assertThat(result.requestPayload()).contains("sender=SHRSTA");
    }

    @Test
    void sendsFlowV5JsonWhenConfigured() throws Exception {
        CustomerSmsProperties properties = baseProperties();
        properties.setMsg91ApiMode("flow-v5");
        properties.setMsg91Endpoint("https://control.msg91.com/api/v5/flow");
        properties.setMsg91TemplateId("flow_template_otp");
        properties.setMsg91TemplateVariableKey("OTP");

        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"request_id\":\"req_flow_2\"}");
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(response);

        Msg91SmsProviderClient client = new Msg91SmsProviderClient(properties, httpClient);
        SmsProviderResult result = client.send("+91 98765 43210", "Your SHRESTA verification OTP is 654321.");

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("req_flow_2");
        assertThat(result.requestPayload()).contains("\"template_id\":\"flow_template_otp\"");
        assertThat(result.requestPayload()).contains("\"mobiles\":\"919876543210\"");
        assertThat(result.requestPayload()).contains("\"OTP\":\"654321\"");
    }

    @Test
    void rejectsFlowModeWithoutTemplateId() {
        CustomerSmsProperties properties = baseProperties();
        properties.setMsg91ApiMode("flow-v5");
        properties.setMsg91TemplateId(" ");

        Msg91SmsProviderClient client = new Msg91SmsProviderClient(properties, HttpClient.newHttpClient());
        SmsProviderResult result = client.send("9876543210", "Your SHRESTA verification OTP is 123456.");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("MSG91 configuration is incomplete.");
        assertThat(result.httpStatus()).isNull();
    }

    @Test
    void surfacesRequestFailures() throws Exception {
        CustomerSmsProperties properties = baseProperties();

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new IOException("connection reset"));

        Msg91SmsProviderClient client = new Msg91SmsProviderClient(properties, httpClient);
        SmsProviderResult result = client.send("9876543210", "hello");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("MSG91 request failed");
    }

    private static CustomerSmsProperties baseProperties() {
        CustomerSmsProperties properties = new CustomerSmsProperties();
        properties.setMsg91ApiMode("legacy-form");
        properties.setMsg91AuthKey("auth-key");
        properties.setMsg91Endpoint("https://api.msg91.com/api/v2/sendsms");
        properties.setMsg91Sender("SHRSTA");
        properties.setMsg91Route("4");
        properties.setMsg91Country("91");
        return properties;
    }
}
