package com.malefashion.order.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

/**
 * Header danh tính nội bộ và tiện ích đọc JSON cho test REST. Giá trị key khớp
 * {@code order.internal-api-key} mà {@link IntegrationTestBase} đăng ký.
 */
public final class HttpTestSupport {

    public static final String INTERNAL_API_KEY = "test-internal-api-key";
    public static final String TRACE_ID = TestOrderProperties.TRACE_ID;
    public static final String CALLER_SERVICE = "api-gateway";

    private HttpTestSupport() {
    }

    public static HttpHeaders baseHeaders(UUID userId, String roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Api-Key", INTERNAL_API_KEY);
        headers.set("X-Caller-Service", CALLER_SERVICE);
        headers.set("X-Trace-Id", TRACE_ID);
        if (userId != null) {
            headers.set("X-User-Id", userId.toString());
        }
        if (roles != null) {
            headers.set("X-User-Roles", roles);
        }
        return headers;
    }

    public static HttpHeaders checkoutHeaders(UUID userId, String roles, UUID idempotencyKey) {
        HttpHeaders headers = baseHeaders(userId, roles);
        headers.set("X-User-Email", OrderFixtures.USER_EMAIL);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey.toString());
        }
        return headers;
    }

    public static JsonNode json(ObjectMapper objectMapper, ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (JsonProcessingException failure) {
            throw new AssertionError("Response is not JSON: " + response.getBody(), failure);
        }
    }

    public static String code(ObjectMapper objectMapper, ResponseEntity<String> response) {
        return json(objectMapper, response).path("code").asText();
    }
}
