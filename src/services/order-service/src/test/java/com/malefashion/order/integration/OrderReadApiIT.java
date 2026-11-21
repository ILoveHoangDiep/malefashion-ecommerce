package com.malefashion.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.OrderStatus;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.WebIntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D.9: quyền xem đơn, lịch sử đơn phân trang/sort/lọc, projection ADMIN và không N+1.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class OrderReadApiIT extends WebIntegrationTestBase {

    private static final UUID USER_B =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ADMIN =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID TIE_LOW =
            UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID TIE_HIGH =
            UUID.fromString("00000000-0000-4000-8000-000000000002");

    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private UUID a1;
    private UUID a2;
    private UUID a3;
    private UUID b1;
    private UUID adminOrder;

    @BeforeEach
    void seedOrders() {
        a1 = seed(UUID.randomUUID(), OrderFixtures.USER_ID, "MF-20261121-AAAAA1",
                BASE.minusSeconds(10), OrderStatus.CONFIRMED);
        a2 = seed(UUID.randomUUID(), OrderFixtures.USER_ID, "MF-20261121-AAAAA2",
                BASE.minusSeconds(20), OrderStatus.PENDING);
        a3 = seed(UUID.randomUUID(), OrderFixtures.USER_ID, "MF-20261121-AAAAA3",
                BASE.minusSeconds(30), OrderStatus.CANCELLED);
        seed(TIE_HIGH, OrderFixtures.USER_ID, "MF-20261121-TIEHI1",
                BASE.minusSeconds(40), OrderStatus.PENDING);
        seed(TIE_LOW, OrderFixtures.USER_ID, "MF-20261121-TIELO1",
                BASE.minusSeconds(40), OrderStatus.PENDING);
        b1 = seed(UUID.randomUUID(), USER_B, "MF-20261121-BBBBB1",
                BASE.minusSeconds(5), OrderStatus.PENDING);
        adminOrder = seed(UUID.randomUUID(), ADMIN, "MF-20261121-ADMIN1",
                BASE.minusSeconds(5), OrderStatus.PENDING);
    }

    @Test
    void ownerReadsOwnOrderButOthersGet404AndAdminSeesAny() {
        ResponseEntity<String> owner = get("/api/v1/orders/" + a1);
        assertThat(owner.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(owner);
        assertThat(body.path("orderId").asText()).isEqualTo(a1.toString());
        assertThat(body.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(body.path("items")).hasSize(1);
        // CUSTOMER không thấy chi tiết saga.
        assertThat(body.has("sagaStep")).isFalse();
        assertThat(body.has("commitUnconfirmed")).isFalse();

        ResponseEntity<String> stranger = get("/api/v1/orders/" + a1, USER_B, "CUSTOMER");
        assertThat(stranger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(stranger)).isEqualTo("ORDER_NOT_FOUND");

        ResponseEntity<String> unknown = get("/api/v1/orders/" + UUID.randomUUID());
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(unknown)).isEqualTo("ORDER_NOT_FOUND");

        ResponseEntity<String> admin = get("/api/v1/orders/" + a1, ADMIN, "ADMIN");
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode adminBody = json(admin);
        assertThat(adminBody.path("sagaStep").asText()).isNotBlank();
        assertThat(adminBody.path("commitUnconfirmed").isBoolean()).isTrue();
    }

    @Test
    void adminSeesCommitUnconfirmedFlag() {
        UUID unconfirmed = UUID.randomUUID();
        Order order = seedOrder(unconfirmed, OrderFixtures.USER_ID, "MF-20261121-UNCNF1",
                BASE.minusSeconds(15), OrderStatus.PENDING);
        order.markCommitUnconfirmed(BASE.minusSeconds(1));
        orderRepository.saveAndFlush(order);

        ResponseEntity<String> admin = get("/api/v1/orders/" + unconfirmed, ADMIN, "ADMIN");
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(admin).path("status").asText()).isEqualTo("FAILED");
        assertThat(json(admin).path("sagaStep").asText()).isEqualTo("DONE");
        assertThat(json(admin).path("commitUnconfirmed").asBoolean()).isTrue();

        ResponseEntity<String> customer = get("/api/v1/orders/" + unconfirmed);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(customer).has("commitUnconfirmed")).isFalse();
        assertThat(json(customer).has("sagaStep")).isFalse();
    }

    @Test
    void listsOwnOrdersNewestFirstWithIdTieBreak() {
        ResponseEntity<String> response = get("/api/v1/orders?size=10");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        assertThat(body.path("page").asInt()).isZero();
        assertThat(body.path("size").asInt()).isEqualTo(10);
        assertThat(body.path("totalElements").asLong()).isEqualTo(5);
        assertThat(body.path("totalPages").asInt()).isEqualTo(1);
        assertThat(orderIds(body)).containsExactly(a1, a2, a3, TIE_HIGH, TIE_LOW);
    }

    @Test
    void paginatesByPageAndSize() {
        JsonNode first = json(get("/api/v1/orders?page=0&size=2&sort=createdAt,desc"));
        assertThat(orderIds(first)).containsExactly(a1, a2);
        assertThat(first.path("totalElements").asLong()).isEqualTo(5);
        assertThat(first.path("totalPages").asInt()).isEqualTo(3);

        JsonNode last = json(get("/api/v1/orders?page=2&size=2"));
        assertThat(orderIds(last)).containsExactly(TIE_LOW);
        assertThat(last.path("page").asInt()).isEqualTo(2);
    }

    @Test
    void filtersByStatus() {
        JsonNode confirmed = json(get("/api/v1/orders?status=CONFIRMED"));
        assertThat(orderIds(confirmed)).containsExactly(a1);

        JsonNode cancelled = json(get("/api/v1/orders?status=cancelled"));
        assertThat(orderIds(cancelled)).containsExactly(a3);

        ResponseEntity<String> invalid = get("/api/v1/orders?status=BOGUS");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(invalid)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void rejectsInvalidPagingAndSortParameters() {
        ResponseEntity<String> tooLarge = get("/api/v1/orders?size=101");
        assertThat(tooLarge.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(tooLarge)).isEqualTo("INVALID_PAGE_SIZE");

        assertThat(get("/api/v1/orders?size=0").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/orders?page=-1").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/orders?sort=createdAt,asc").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/orders?page=abc").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listIsScopedToCallerEvenForAdmin() {
        JsonNode adminList = json(get("/api/v1/orders", ADMIN, "ADMIN"));
        assertThat(orderIds(adminList)).containsExactly(adminOrder);

        JsonNode userList = json(get("/api/v1/orders", USER_B, "CUSTOMER"));
        assertThat(orderIds(userList)).containsExactly(b1);
    }

    @Test
    void listLoadsItemsInOneBatchWithoutNPlusOne() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        ResponseEntity<String> response = get("/api/v1/orders?size=10");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).path("content")).hasSize(5);
        // count + page query + một query batch items = 3; cho phép dư 2 statement phát sinh.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(5);
    }

    private UUID seed(UUID id, UUID userId, String orderNumber, Instant createdAt,
                      OrderStatus status) {
        return seedOrder(id, userId, orderNumber, createdAt, status).getId();
    }

    private Order seedOrder(UUID id, UUID userId, String orderNumber, Instant createdAt,
                            OrderStatus status) {
        Order order = Order.create(id, orderNumber, userId, "user-" + userId + "@example.com",
                new Order.ShippingSnapshot("Nguyen Van An", "0901234567", "1 Dai Co Viet",
                        "Le Dai Hanh", "Hai Ba Trung", "Ha Noi"),
                new Order.MoneySnapshot(new BigDecimal("100000.00"), new BigDecimal("0.00"),
                        new BigDecimal("30000.00"), new BigDecimal("130000.00")),
                "SUMMER10", null, createdAt.plus(Duration.ofMinutes(10)), createdAt);
        order.addItem(OrderFixtures.VARIANT_ID, "MF-TEE-BLACK-M", "Basic Tee", "M", "BLACK", 1,
                new BigDecimal("100000.00"));
        if (status == OrderStatus.CONFIRMED) {
            order.confirm(createdAt.plusSeconds(1));
        } else if (status == OrderStatus.CANCELLED) {
            order.cancel(CancelReason.PAYMENT_FAILED, createdAt.plusSeconds(1));
        }
        orderRepository.saveAndFlush(order);
        return order;
    }

    private List<UUID> orderIds(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("content").forEach(order ->
                ids.add(UUID.fromString(order.path("orderId").asText())));
        return ids;
    }
}
