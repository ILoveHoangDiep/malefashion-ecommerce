package com.malefashion.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.SagaStep;
import com.malefashion.order.infrastructure.messaging.MessageTypes;
import com.malefashion.order.infrastructure.messaging.MessagingTopology;
import com.malefashion.order.infrastructure.persistence.OrderRepository;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.support.MessagingIntegrationTestBase;
import com.malefashion.order.support.OrderFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C.15: RabbitMQ thật, listener thật và handler thật. Chỉ chứng minh đường dây broker → inbox →
 * saga → outbox; test Postgres-only đã phủ chi tiết trạng thái.
 */
class OrderSagaRabbitFlowIT extends MessagingIntegrationTestBase {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void paymentSucceededConsumedFromRabbitMqProducesCommitCommand() throws Exception {
        Instant now = Instant.now();
        Order order = OrderFixtures.newOrder(now, now.plus(Duration.ofMinutes(10)));
        order.applyReservation(OrderFixtures.RESERVATION_ID, now.plus(Duration.ofMinutes(15)),
                now);
        order.moveToStep(SagaStep.AWAITING_PAYMENT, now);
        orderRepository.saveAndFlush(order);

        JsonNode example = firstExample("payment-succeeded.json");
        UUID eventId = UUID.fromString(example.get("eventId").asText());

        sendRawEvent(MessageTypes.EVENT_PAYMENT_SUCCEEDED, eventId, json(example));
        awaitProcessed(eventId);

        Order updated = orderRepository.findById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(updated.getSagaStep()).isEqualTo(SagaStep.AWAITING_COMMIT);
        assertThat(updated.getCommitCommandEventId()).isNotNull();

        List<OutboxEvent> commits = outboxEventRepository.findAll().stream()
                .filter(event -> MessageTypes.COMMAND_COMMIT_STOCK.equals(event.getEventType()))
                .toList();
        assertThat(commits).hasSize(1);
        assertThat(commits.getFirst().getEventId()).isEqualTo(updated.getCommitCommandEventId());
        assertThat(commits.getFirst().getExchange())
                .isEqualTo(MessagingTopology.COMMANDS_EXCHANGE);
        assertThat(messageCount(MessagingTopology.ORDER_SAGA_EVENTS_DLQ)).isZero();
    }
}
