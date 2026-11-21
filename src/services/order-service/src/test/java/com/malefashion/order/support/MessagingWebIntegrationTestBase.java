package com.malefashion.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

/**
 * RabbitMQ thật + REST ở cổng thật + WireMock cart/catalog cho luồng end-to-end của GĐ3.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class MessagingWebIntegrationTestBase extends MessagingIntegrationTestBase {

    @DynamicPropertySource
    static void registerUpstream(DynamicPropertyRegistry registry) {
        WireMockUpstream.register(registry);
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @BeforeEach
    void resetUpstream() {
        WireMockUpstream.reset();
    }

    protected ResponseEntity<String> checkout(String body, UUID idempotencyKey) {
        return restTemplate.exchange("/api/v1/orders/checkout", HttpMethod.POST,
                new HttpEntity<>(body, HttpTestSupport.checkoutHeaders(OrderFixtures.USER_ID,
                        "CUSTOMER", idempotencyKey)), String.class);
    }

    protected ResponseEntity<String> getOrder(String path) {
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(HttpTestSupport.baseHeaders(OrderFixtures.USER_ID, "CUSTOMER")),
                String.class);
    }

    protected JsonNode json(ResponseEntity<String> response) {
        return HttpTestSupport.json(objectMapper, response);
    }

    protected HttpHeaders headers() {
        return HttpTestSupport.baseHeaders(OrderFixtures.USER_ID, "CUSTOMER");
    }
}
