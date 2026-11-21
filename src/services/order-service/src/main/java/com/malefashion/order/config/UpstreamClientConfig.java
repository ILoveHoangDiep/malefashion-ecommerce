package com.malefashion.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Hai RestClient riêng cho cart và catalog với connect timeout 2s, read timeout 5s. Retry chỉ áp
 * dụng cho GET (xem {@code CartClient}); catalog batch là POST và không retry.
 */
@Configuration
public class UpstreamClientConfig {

    static final int CONNECT_TIMEOUT_MS = 2000;
    static final int READ_TIMEOUT_MS = 5000;

    @Bean
    public RestClient cartRestClient(OrderProperties properties) {
        return build(properties.cartBaseUrl());
    }

    @Bean
    public RestClient catalogRestClient(OrderProperties properties) {
        return build(properties.catalogBaseUrl());
    }

    private RestClient build(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
