package com.malefashion.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.order.infrastructure.persistence.CheckoutIdempotencyRepository;
import com.malefashion.order.infrastructure.persistence.OrderRepository;
import com.malefashion.order.infrastructure.persistence.OutboxEventRepository;
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
 * Spring Boot chạy ở cổng thật + WireMock cho cart/catalog. PostgreSQL dùng chung container của
 * {@link IntegrationTestBase}; listener và outbox publisher vẫn tắt vì không cần RabbitMQ.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class WebIntegrationTestBase extends IntegrationTestBase {

    @DynamicPropertySource
    static void registerUpstream(DynamicPropertyRegistry registry) {
        WireMockUpstream.register(registry);
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected OrderRepository orderRepository;

    @Autowired
    protected CheckoutIdempotencyRepository idempotencyRepository;

    @Autowired
    protected OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void resetUpstream() {
        WireMockUpstream.reset();
    }

    protected ResponseEntity<String> checkout(String body, UUID idempotencyKey) {
        return checkout(body, idempotencyKey, OrderFixtures.USER_ID, "CUSTOMER");
    }

    protected ResponseEntity<String> checkout(String body, UUID idempotencyKey, UUID userId,
                                              String roles) {
        return exchange(HttpMethod.POST, "/api/v1/orders/checkout",
                HttpTestSupport.checkoutHeaders(userId, roles, idempotencyKey), body);
    }

    protected ResponseEntity<String> get(String path) {
        return get(path, OrderFixtures.USER_ID, "CUSTOMER");
    }

    protected ResponseEntity<String> get(String path, UUID userId, String roles) {
        return exchange(HttpMethod.GET, path, HttpTestSupport.baseHeaders(userId, roles), null);
    }

    protected ResponseEntity<String> exchange(HttpMethod method, String path, HttpHeaders headers,
                                              String body) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    protected JsonNode json(ResponseEntity<String> response) {
        return HttpTestSupport.json(objectMapper, response);
    }

    protected String errorCode(ResponseEntity<String> response) {
        return HttpTestSupport.code(objectMapper, response);
    }
}
