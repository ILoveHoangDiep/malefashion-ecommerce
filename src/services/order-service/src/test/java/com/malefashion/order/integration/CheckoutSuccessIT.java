package com.malefashion.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.OrderStatus;
import com.malefashion.order.domain.model.SagaStep;
import com.malefashion.order.infrastructure.messaging.MessageTypes;
import com.malefashion.order.infrastructure.messaging.MessagingTopology;
import com.malefashion.order.infrastructure.persistence.CheckoutIdempotency;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.support.ContractSchemas;
import com.malefashion.order.support.HttpTestSupport;
import com.malefashion.order.support.OrderApiContract;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.UpstreamFixtures;
import com.malefashion.order.support.WebIntegrationTestBase;
import com.malefashion.order.support.WireMockUpstream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D.1 + D.2: checkout thành công ghi đúng order/items/idempotency/outbox và response khớp
 * order-api.yaml.
 */
class CheckoutSuccessIT extends WebIntegrationTestBase {

    @Test
    void createsPendingOrderWithCatalogPricingAndTwoOutboxEvents() throws Exception {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog();
        UUID idempotencyKey = UUID.randomUUID();

        ResponseEntity<String> response = checkout(UpstreamFixtures.checkoutBody(), idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json(response);
        UUID orderId = UUID.fromString(body.path("orderId").asText());
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("orderNumber").asText()).matches("^MF-\\d{8}-[A-Z0-9]{6}$");
        assertThat(body.path("userId").asText()).isEqualTo(OrderFixtures.USER_ID.toString());
        assertThat(body.path("currency").asText()).isEqualTo("VND");

        // Giá catalog 275000 × 2 = 550000, trừ coupon 50000, cộng ship 30000.
        assertThat(body.path("subtotal").path("amount").asText()).isEqualTo("550000.00");
        assertThat(body.path("discountAmount").path("amount").asText()).isEqualTo("50000.00");
        assertThat(body.path("shippingFee").path("amount").asText()).isEqualTo("30000.00");
        assertThat(body.path("totalAmount").path("amount").asText()).isEqualTo("530000.00");
        JsonNode item = body.path("items").get(0);
        assertThat(item.path("variantId").asText()).isEqualTo(OrderFixtures.VARIANT_ID.toString());
        assertThat(item.path("sku").asText()).isEqualTo("MF-TEE-BLACK-M");
        assertThat(item.path("quantity").asInt()).isEqualTo(2);
        assertThat(item.path("unitPrice").path("amount").asText())
                .isEqualTo(UpstreamFixtures.CATALOG_PRICE);
        assertThat(item.path("lineTotal").path("amount").asText()).isEqualTo("550000.00");
        assertThat(body.path("shippingAddress").path("city").asText()).isEqualTo("Ha Noi");

        Order order = orderRepository.findWithItemsById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_RESERVATION);
        assertThat(order.getUserEmail()).isEqualTo(OrderFixtures.USER_EMAIL);
        assertThat(order.getCustomerName()).isEqualTo("Nguyen Van An");
        assertThat(order.getCouponCode()).isEqualTo("SUMMER10");
        assertThat(order.getMockPaymentScenario()).isNull();
        assertThat(order.getSubtotal()).isEqualByComparingTo("550000.00");
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("50000.00");
        assertThat(order.getShippingFee()).isEqualByComparingTo("30000.00");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("530000.00");
        assertThat(order.getSagaDeadlineAt())
                .isEqualTo(order.getCreatedAt().plus(Duration.ofMinutes(10)));
        assertThat(order.getItems()).singleElement().satisfies(saved -> {
            assertThat(saved.getUnitPrice()).isEqualByComparingTo(new BigDecimal("275000.00"));
            assertThat(saved.getLineTotal()).isEqualByComparingTo(new BigDecimal("550000.00"));
        });

        CheckoutIdempotency record = idempotencyRepository
                .findByUserIdAndIdempotencyKey(OrderFixtures.USER_ID, idempotencyKey)
                .orElseThrow();
        assertThat(record.getOrderId()).isEqualTo(orderId);
        assertThat(record.getRequestHash()).hasSize(64);

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(2);
        OutboxEvent created = single(events, MessageTypes.EVENT_ORDER_CREATED);
        OutboxEvent reserve = single(events, MessageTypes.COMMAND_RESERVE_STOCK);

        assertThat(created.getExchange()).isEqualTo(MessagingTopology.EVENTS_EXCHANGE);
        assertThat(created.getRoutingKey()).isEqualTo(MessageTypes.EVENT_ORDER_CREATED);
        assertThat(created.getAggregateId()).isEqualTo(orderId);
        assertThat(created.getPayload().path("correlationId").asText())
                .isEqualTo(orderId.toString());
        assertThat(created.getPayload().path("traceId").asText())
                .isEqualTo(HttpTestSupport.TRACE_ID);
        assertThat(created.getPayload().path("payload").path("totalAmount").path("amount")
                .asText()).isEqualTo("530000.00");

        assertThat(reserve.getExchange()).isEqualTo(MessagingTopology.COMMANDS_EXCHANGE);
        assertThat(reserve.getRoutingKey()).isEqualTo(MessageTypes.COMMAND_RESERVE_STOCK);
        assertThat(reserve.getAggregateId()).isEqualTo(orderId);
        assertThat(reserve.getPayload().path("correlationId").asText())
                .isEqualTo(orderId.toString());
        assertThat(reserve.getPayload().path("traceId").asText())
                .isEqualTo(HttpTestSupport.TRACE_ID);
        assertThat(reserve.getPayload().path("payload").path("orderId").asText())
                .isEqualTo(orderId.toString());

        ContractSchemas.validate(created.getEventType(),
                objectMapper.writeValueAsString(created.getPayload()));
        ContractSchemas.validate(reserve.getEventType(),
                objectMapper.writeValueAsString(reserve.getPayload()));

        // Header gọi nội bộ đúng contract của cart/catalog.
        WireMockUpstream.SERVER.verify(getRequestedFor(
                        urlEqualTo(UpstreamFixtures.cartPath(OrderFixtures.USER_ID)))
                .withHeader("X-Internal-Api-Key", equalTo(HttpTestSupport.INTERNAL_API_KEY))
                .withHeader("X-Caller-Service", equalTo("order-service"))
                .withHeader("X-Trace-Id", equalTo(HttpTestSupport.TRACE_ID))
                .withHeader("X-User-Id", equalTo(OrderFixtures.USER_ID.toString())));
        WireMockUpstream.SERVER.verify(1, postRequestedFor(
                urlEqualTo(WireMockUpstream.CATALOG_BATCH_PATH)));
        // Checkout KHÔNG xóa giỏ: việc dọn giỏ thuộc cart-service khi nhận order.confirmed.
        WireMockUpstream.SERVER.verify(0, deleteRequestedFor(anyUrl()));
    }

    @Test
    void responsesMatchOrderApiContract() {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog();
        UUID idempotencyKey = UUID.randomUUID();

        ResponseEntity<String> created = checkout(UpstreamFixtures.checkoutBody(), idempotencyKey);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderApiContract.assertResponseValid("/api/v1/orders/checkout", "post", created);

        ResponseEntity<String> replayed = checkout(UpstreamFixtures.checkoutBody(),
                idempotencyKey);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        OrderApiContract.assertResponseValid("/api/v1/orders/checkout", "post", replayed);

        UUID orderId = UUID.fromString(json(created).path("orderId").asText());
        ResponseEntity<String> single = get("/api/v1/orders/" + orderId);
        assertThat(single.getStatusCode()).isEqualTo(HttpStatus.OK);
        OrderApiContract.assertResponseValid("/api/v1/orders/" + orderId, "get", single);

        ResponseEntity<String> page = get("/api/v1/orders");
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        OrderApiContract.assertResponseValid("/api/v1/orders", "get", page);

        // Lỗi validate body.
        WireMockUpstream.reset();
        ResponseEntity<String> invalid = checkout("{}", UUID.randomUUID());
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        OrderApiContract.assertResponseValid("/api/v1/orders/checkout", "post", invalid);

        // Thiếu X-User-Id.
        HttpHeaders headers = HttpTestSupport.checkoutHeaders(null, "CUSTOMER", UUID.randomUUID());
        headers.remove("X-User-Id");
        ResponseEntity<String> unauthenticated = exchange(org.springframework.http.HttpMethod.POST,
                "/api/v1/orders/checkout", headers, UpstreamFixtures.checkoutBody());
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        OrderApiContract.assertResponseValid("/api/v1/orders/checkout", "post", unauthenticated);

        // Giỏ rỗng.
        WireMockUpstream.stubCart(OrderFixtures.USER_ID,
                UpstreamFixtures.emptyCart(OrderFixtures.USER_ID));
        ResponseEntity<String> emptyCart = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());
        assertThat(emptyCart.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        OrderApiContract.assertResponseValid("/api/v1/orders/checkout", "post", emptyCart);

        // Cart lỗi 5xx sau retry.
        WireMockUpstream.reset();
        WireMockUpstream.SERVER.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.get(
                                urlEqualTo(UpstreamFixtures.cartPath(OrderFixtures.USER_ID)))
                        .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                                .aResponse().withStatus(500)));
        ResponseEntity<String> unavailable = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        OrderApiContract.assertResponseValid("/api/v1/orders/checkout", "post", unavailable);
    }

    private static OutboxEvent single(List<OutboxEvent> events, String eventType) {
        return events.stream()
                .filter(event -> eventType.equals(event.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing outbox event " + eventType));
    }
}
