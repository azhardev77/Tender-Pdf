package com.azhardev.tenderengine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${app.llm.base-url:}")
    private String baseUrl;

    @Value("${app.llm.timeout-ms:30000}")
    private int timeoutMs;

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        RestClient.Builder customBuilder = builder.requestFactory(requestFactory);
        if (baseUrl != null && !baseUrl.isBlank()) {
            customBuilder.baseUrl(baseUrl);
        }
        return customBuilder.build();
    }
}
