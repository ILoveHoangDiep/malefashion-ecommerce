package com.malefashion.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.OrderStatus;
import com.malefashion.order.domain.model.SagaStep;
import com.malefashion.order.infrastructure.messaging.MessageTypes;
import com.malefashion.order.infrastructure.messaging.MessagingTopology;
import com.malefashion.order.infrastructure.persistence.OrderRepository;
import com.malefashion.order.support.MessagingWebIntegrationTestBase;
import com.malefashion.order.support.OrderApiContract;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.UpstreamFixtures;
import com.malefashion.order.support.WireMockUpstream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D.11: checkout thật → outbox publisher → RabbitMQ → giả lập inventory/payment → saga thật →
 * CONFIRMED, rồi GET qua REST trả CONFIRMED.
 *
 * <p>inventory-service và payment-service không chạy trong test; test đọc command từ queue của
 * chúng và phát event contract example thay cho phản hồi thật, nhưng toàn bộ đường dây
 * outbox/broker/listener/inbox/saga là thật.</p>
 */
class CheckoutRabbitFlowIT extends MessagingWebIntegrationTestBase {

    private static final String PAYMENT_COMMANDS_QUEUE = "payment-service.commands";

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void checkoutBecomesConfirmedThroughRealBroker() throws Exception {
        purgeQueue(PAYMENT_COMMANDS_QUEUE);
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog();

        ResponseEntity<String> checkout = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());
        assertThat(checkout.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID orderId = UUID.fromString(json(checkout).path("orderId").asText());

        outboxPublisher.publishPendingEvents();
        Message reserve = awaitMessage(MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        assertThat(reserve.getMessageProperties().getType())
                .isEqualTo(MessageTypes.COMMAND_RESERVE_STOCK);
        assertThat(reserve.getMessageProperties().getReceivedRoutingKey())
                .isEqualTo(MessageTypes.COMMAND_RESERVE_STOCK);
        JsonNode reservePayload = objectMapper.readTree(reserve.getBody()).path("payload");
        assertThat(reservePayload.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(reservePayload.path("items")).hasSize(1);
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> messageCount(MessagingTopology.NOTIFICATION_EVENTS_QUEUE) >= 1);

        // inventory-service giả lập giữ chỗ thành công.
        JsonNode reserved = example("inventory-stock-reserved.json");
        retarget(reserved, orderId);
        UUID reservedEventId = UUID.randomUUID();
        ((ObjectNode) reserved).put("eventId", reservedEventId.toString());
        sendRawEvent(MessageTypes.EVENT_STOCK_RESERVED, reservedEventId, json(reserved));
        awaitProcessed(reservedEventId);

        outboxPublisher.publishPendingEvents();
        Message payment = awaitMessage(PAYMENT_COMMANDS_QUEUE);
        assertThat(payment.getMessageProperties().getType())
                .isEqualTo(MessageTypes.COMMAND_PROCESS_PAYMENT);
        JsonNode paymentPayload = objectMapper.readTree(payment.getBody()).path("payload");
        assertThat(paymentPayload.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(paymentPayload.path("idempotencyKey").asText()).isEqualTo(orderId.toString());
        assertThat(paymentPayload.path("amount").path("amount").asText()).isEqualTo("530000.00");
        assertThat(paymentPayload.hasNonNull("mockScenario")).isFalse();
        assertThat(reload(orderId).getSagaStep()).isEqualTo(SagaStep.AWAITING_PAYMENT);

        // payment-service giả lập thanh toán thành công.
        JsonNode paid = example("payment-succeeded.json");
        retarget(paid, orderId);
        ((ObjectNode) paid.path("payload").path("amount")).put("amount", "530000.00");
        UUID paidEventId = UUID.randomUUID();
        ((ObjectNode) paid).put("eventId", paidEventId.toString());
        sendRawEvent(MessageTypes.EVENT_PAYMENT_SUCCEEDED, paidEventId, json(paid));
        awaitProcessed(paidEventId);

        outboxPublisher.publishPendingEvents();
        Message commit = awaitMessage(MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        assertThat(commit.getMessageProperties().getType())
                .isEqualTo(MessageTypes.COMMAND_COMMIT_STOCK);
        JsonNode commitPayload = objectMapper.readTree(commit.getBody()).path("payload");
        assertThat(commitPayload.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(commitPayload.path("reservationId").asText())
                .isEqualTo(reserved.path("payload").path("reservationId").asText());

        // inventory-service giả lập chốt kho thành công.
        JsonNode committed = example("inventory-stock-committed.json");
        retarget(committed, orderId);
        UUID committedEventId = UUID.randomUUID();
        ((ObjectNode) committed).put("eventId", committedEventId.toString());
        sendRawEvent(MessageTypes.EVENT_STOCK_COMMITTED, committedEventId, json(committed));
        awaitProcessed(committedEventId);

        awaitStatus(orderId, OrderStatus.CONFIRMED);
        outboxPublisher.publishPendingEvents();
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> messageCount(MessagingTopology.NOTIFICATION_EVENTS_QUEUE) >= 2);

        ResponseEntity<String> read = getOrder("/api/v1/orders/" + orderId);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(read).path("status").asText()).isEqualTo("CONFIRMED");
        OrderApiContract.assertResponseValid("/api/v1/orders/" + orderId, "get", read);
    }

    private Order reload(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    private void awaitStatus(UUID orderId, OrderStatus status) {
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(reload(orderId).getStatus()).isEqualTo(status));
    }

    private JsonNode example(String schemaFile) throws Exception {
        return firstExample(schemaFile);
    }

    private void retarget(JsonNode envelope, UUID orderId) {
        ((ObjectNode) envelope).put("correlationId", orderId.toString());
        ((ObjectNode) envelope.path("payload")).put("orderId", orderId.toString());
    }

    private Message awaitMessage(String queue) {
        AtomicReference<Message> holder = new AtomicReference<>();
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    Message message = rabbitTemplate.receive(queue, 200);
                    if (message == null) {
                        return false;
                    }
                    holder.set(message);
                    return true;
                });
        return holder.get();
    }
}
