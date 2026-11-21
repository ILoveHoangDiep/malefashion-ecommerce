package com.malefashion.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.order.support.HttpTestSupport;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.UpstreamFixtures;
import com.malefashion.order.support.WebIntegrationTestBase;
import com.malefashion.order.support.WireMockUpstream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D.4 + D.5: header thiếu/sai và dữ liệu giỏ/body không hợp lệ. Mã lỗi lấy theo order-api.yaml
 * khi contract có quy định (CART_EMPTY, VARIANT_UNAVAILABLE, CART_CHANGED...).
 */
class CheckoutValidationIT extends WebIntegrationTestBase {

    private static final String CHECKOUT = "/api/v1/orders/checkout";

    @Test
    void rejectsMissingOrWrongInternalApiKey() {
        HttpHeaders missing = checkoutHeaders();
        missing.remove("X-Internal-Api-Key");
        assertProblem(exchange(HttpMethod.POST, CHECKOUT, missing,
                UpstreamFixtures.checkoutBody()), HttpStatus.UNAUTHORIZED,
                "INVALID_INTERNAL_API_KEY");

        HttpHeaders wrong = checkoutHeaders();
        wrong.set("X-Internal-Api-Key", "wrong-key");
        assertProblem(exchange(HttpMethod.POST, CHECKOUT, wrong,
                UpstreamFixtures.checkoutBody()), HttpStatus.UNAUTHORIZED,
                "INVALID_INTERNAL_API_KEY");
    }

    @Test
    void rejectsMissingCallerService() {
        HttpHeaders headers = checkoutHeaders();
        headers.remove("X-Caller-Service");

        assertProblem(exchange(HttpMethod.POST, CHECKOUT, headers,
                UpstreamFixtures.checkoutBody()), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    @Test
    void rejectsMissingOrMalformedUserId() {
        HttpHeaders missing = checkoutHeaders();
        missing.remove("X-User-Id");
        assertProblem(exchange(HttpMethod.POST, CHECKOUT, missing,
                UpstreamFixtures.checkoutBody()), HttpStatus.UNAUTHORIZED, "MISSING_USER_CONTEXT");

        HttpHeaders malformed = checkoutHeaders();
        malformed.set("X-User-Id", "not-a-uuid");
        assertProblem(exchange(HttpMethod.POST, CHECKOUT, malformed,
                UpstreamFixtures.checkoutBody()), HttpStatus.UNAUTHORIZED, "MISSING_USER_CONTEXT");
    }

    @Test
    void rejectsMissingOrMalformedUserEmail() {
        HttpHeaders missing = checkoutHeaders();
        missing.remove("X-User-Email");
        assertProblem(exchange(HttpMethod.POST, CHECKOUT, missing,
                UpstreamFixtures.checkoutBody()), HttpStatus.UNAUTHORIZED, "MISSING_USER_EMAIL");

        HttpHeaders malformed = checkoutHeaders();
        malformed.set("X-User-Email", "not-an-email");
        assertProblem(exchange(HttpMethod.POST, CHECKOUT, malformed,
                UpstreamFixtures.checkoutBody()), HttpStatus.UNAUTHORIZED, "MISSING_USER_EMAIL");
    }

    @Test
    void rejectsRoleOtherThanCustomerOrAdmin() {
        assertProblem(exchange(HttpMethod.POST, CHECKOUT,
                        HttpTestSupport.checkoutHeaders(OrderFixtures.USER_ID, "STAFF",
                                UUID.randomUUID()),
                        UpstreamFixtures.checkoutBody()),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    @Test
    void rejectsMissingOrMalformedIdempotencyKey() {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog();

        assertProblem(checkout(UpstreamFixtures.checkoutBody(), null),
                HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY");

        HttpHeaders malformed = checkoutHeaders();
        malformed.set("Idempotency-Key", "not-a-uuid");
        assertProblem(exchange(HttpMethod.POST, CHECKOUT, malformed,
                UpstreamFixtures.checkoutBody()), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void rejectsMockPaymentScenarioWhenDisabled() {
        assertProblem(checkout(UpstreamFixtures.checkoutBodyWithScenario("SUCCESS"),
                        UUID.randomUUID()),
                HttpStatus.BAD_REQUEST, "MOCK_PAYMENT_NOT_ALLOWED");
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void rejectsEmptyCart() {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID,
                UpstreamFixtures.emptyCart(OrderFixtures.USER_ID));

        ResponseEntity<String> response = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());

        assertProblem(response, HttpStatus.CONFLICT, "CART_EMPTY");
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void rejectsVariantMissingFromCatalog() {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog(UpstreamFixtures.catalogMissing(OrderFixtures.VARIANT_ID));

        assertProblem(checkout(UpstreamFixtures.checkoutBody(), UUID.randomUUID()),
                HttpStatus.CONFLICT, "VARIANT_UNAVAILABLE");
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void rejectsInactiveVariant() {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog(UpstreamFixtures.catalog(OrderFixtures.VARIANT_ID,
                UpstreamFixtures.CATALOG_PRICE, false));

        assertProblem(checkout(UpstreamFixtures.checkoutBody(), UUID.randomUUID()),
                HttpStatus.CONFLICT, "VARIANT_UNAVAILABLE");
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void rejectsCartThatChangedAfterStorefrontLoadedIt() {
        UUID otherCartId = UUID.fromString("11111111-2222-4333-8444-555555555555");
        WireMockUpstream.stubCart(OrderFixtures.USER_ID, UpstreamFixtures.cart(
                OrderFixtures.USER_ID, otherCartId, UpstreamFixtures.CART_PRICE, 2));

        assertProblem(checkout(UpstreamFixtures.checkoutBody(), UUID.randomUUID()),
                HttpStatus.CONFLICT, "CART_CHANGED");
    }

    @Test
    void treatsCartOfAnotherUserAsUpstreamFailure() {
        UUID otherUser = UUID.fromString("11111111-2222-4333-8444-555555555555");
        WireMockUpstream.stubCart(OrderFixtures.USER_ID,
                UpstreamFixtures.cart(otherUser));

        assertProblem(checkout(UpstreamFixtures.checkoutBody(), UUID.randomUUID()),
                HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
    }

    @Test
    void rejectsInvalidCheckoutBodyWithFieldDetails() {
        ResponseEntity<String> empty = checkout("{}", UUID.randomUUID());
        assertProblem(empty, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNames(empty)).contains("cartId", "shippingAddress", "paymentMethod");

        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog();
        ResponseEntity<String> badPhone = checkout(
                UpstreamFixtures.checkoutBody().replace("0901234567", "abc"), UUID.randomUUID());
        assertProblem(badPhone, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNames(badPhone)).contains("shippingAddress.phone");

        ResponseEntity<String> emptyCoupon = checkout(
                UpstreamFixtures.checkoutBody().replace("\"SUMMER10\"", "\"\""),
                UUID.randomUUID());
        assertProblem(emptyCoupon, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNames(emptyCoupon)).contains("couponCode");

        ResponseEntity<String> unknownField = checkout(
                UpstreamFixtures.checkoutBody().replace("\"paymentMethod\": \"MOCK_CARD\"",
                        "\"paymentMethod\": \"MOCK_CARD\", \"unexpected\": true"),
                UUID.randomUUID());
        assertProblem(unknownField, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");

        assertThat(orderRepository.count()).isZero();
    }

    private HttpHeaders checkoutHeaders() {
        return HttpTestSupport.checkoutHeaders(OrderFixtures.USER_ID, "CUSTOMER",
                UUID.randomUUID());
    }

    private void assertProblem(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(contentType -> assertThat(contentType.isCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        JsonNode body = json(response);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("status").asInt()).isEqualTo(status.value());
        assertThat(body.path("title").asText()).isNotBlank();
        assertThat(body.path("type").asText()).startsWith("https://malefashion.local/errors/");
        assertThat(body.path("traceId").asText()).isEqualTo(HttpTestSupport.TRACE_ID);
        assertThat(body.path("timestamp").asText()).isNotBlank();
    }

    private java.util.List<String> fieldNames(ResponseEntity<String> response) {
        JsonNode errors = json(response).path("errors");
        java.util.List<String> names = new java.util.ArrayList<>();
        errors.forEach(error -> names.add(error.path("field").asText()));
        return names;
    }
}
