package com.shrestaexclusive.platform.email.configuration;

import java.net.http.HttpClient;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

@Configuration
public class EmailRestClientConfiguration implements RestClientCustomizer {
    private final EmailProperties properties;

    public EmailRestClientConfiguration(EmailProperties properties) {
        this.properties = properties;
    }

    @Override
    public void customize(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        builder.requestFactory(requestFactory);
    }
}