package com.malefashion.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.order.application.OutboxService;
import com.malefashion.order.infrastructure.messaging.MessageEnvelope;
import com.malefashion.order.infrastructure.messaging.MessageTypes;
import com.malefashion.order.infrastructure.messaging.MessagingTopology;
import com.malefashion.order.infrastructure.messaging.OrderMessageFactory;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.infrastructure.persistence.OutboxEventRepository;
import com.malefashion.order.infrastructure.persistence.OutboxStatus;
import com.malefashion.order.support.ContractSchemas;
import com.malefashion.order.support.MessagingIntegrationTestBase;
import com.malefashion.order.support.OrderFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publisher thật trên RabbitMQ thật: confirm correlated, mandatory + returns, batch xử lý độc lập,
 * at-least-once với dòng lỗi giữ PENDING, messageId là eventId (không phải id dòng outbox).
 */
class OrderOutboxPublisherIT extends MessagingIntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-10-22T03:15:30.123Z");

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private OrderMessageFactory messageFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void publishesCommandWithContractEnvelopeAndAmqpMetadata() {
        UUID eventId = UUID.randomUUID();
        MessageEnvelope<OrderMessageFactory.ReserveStockPayload> envelope =
                messageFactory.reserveStock(eventId, OrderFixtures.newOrder(NOW));
        OutboxEvent row = inTransaction(() -> outboxService.appendCommand(envelope));

        outboxPublisher.publishPendingEvents();

        Message message = receive(MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        assertThat(message).isNotNull();
        MessageProperties properties = message.getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(eventId.toString());
        assertThat(properties.getType()).isEqualTo(MessageTypes.COMMAND_RESERVE_STOCK);
        assertThat(properties.getCorrelationId()).isEqualTo(OrderFixtures.ORDER_ID.toString());
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.getHeaders().get("x-event-version")).isEqualTo(1);

        String body = body(message);
        ContractSchemas.validate(MessageTypes.COMMAND_RESERVE_STOCK, body);
        assertThat(readTree(body)).isEqualTo(row.getPayload());
        String traceId = readTree(body).path("traceId").asText();
        assertThat(traceId).matches("^[0-9a-f]{32}$");
        assertThat((String) properties.getHeaders().get("traceparent"))
                .matches("^00-" + traceId + "-[0-9a-f]{16}-01$");

        OutboxEvent reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(reloaded.getPublishedAt()).isNotNull();
    }

    @Test
    void publishesOrderEventToNotificationQueue() {
        UUID eventId = UUID.randomUUID();
        MessageEnvelope<OrderMessageFactory.OrderCreatedPayload> envelope =
                messageFactory.orderCreated(OrderFixtures.newOrder(NOW));

        OutboxEvent row = inTransaction(() -> outboxService.appendEvent(envelope));
        outboxPublisher.publishPendingEvents();

        Message message = receive(MessagingTopology.NOTIFICATION_EVENTS_QUEUE);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId())
                .isEqualTo(envelope.eventId().toString());
        assertThat(message.getMessageProperties().getType())
                .isEqualTo(MessageTypes.EVENT_ORDER_CREATED);
        assertThat(message.getMessageProperties().getCorrelationId())
                .isEqualTo(OrderFixtures.ORDER_ID.toString());
        ContractSchemas.validate(MessageTypes.EVENT_ORDER_CREATED, body(message));

        assertThat(repository.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void duplicateEventIdRowsArePublishedIndependently() {
        UUID eventId = UUID.randomUUID();
        MessageEnvelope<OrderMessageFactory.CommitStockPayload> envelope =
                messageFactory.commitStock(eventId, OrderFixtures.ORDER_ID,
                        OrderFixtures.RESERVATION_ID);

        inTransaction(() -> {
            outboxService.appendCommand(envelope);
            outboxService.appendCommand(envelope);
            return null;
        });
        outboxPublisher.publishPendingEvents();

        assertThat(repository.findAll())
                .hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.getEventId()).isEqualTo(eventId);
                    assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
                })
                .extracting(OutboxEvent::getId)
                .doesNotHaveDuplicates();

        Message first = receive(MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        Message second = receive(MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
        assertThat(second.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
    }

    @Test
    void unroutableRowStaysPendingWithoutBlockingOtherRowsInBatch() {
        UUID goodEventId = UUID.randomUUID();
        UUID badEventId = UUID.randomUUID();
        MessageEnvelope<OrderMessageFactory.CommitStockPayload> goodEnvelope =
                messageFactory.commitStock(goodEventId, OrderFixtures.ORDER_ID,
                        OrderFixtures.RESERVATION_ID);
        String badRoutingKey = "order.outbox.unroutable." + UUID.randomUUID();

        // Hai dòng cùng batch: một routable, một không có binding nào.
        repository.save(OutboxEvent.pending(
                UUID.randomUUID(),
                badEventId,
                MessageTypes.COMMAND_COMMIT_STOCK,
                OrderFixtures.ORDER_ID,
                MessagingTopology.EVENTS_EXCHANGE,
                badRoutingKey,
                objectMapper.valueToTree(goodEnvelope),
                NOW));
        repository.save(OutboxEvent.pending(
                UUID.randomUUID(),
                goodEventId,
                MessageTypes.COMMAND_COMMIT_STOCK,
                OrderFixtures.ORDER_ID,
                MessagingTopology.COMMANDS_EXCHANGE,
                MessageTypes.COMMAND_COMMIT_STOCK,
                objectMapper.valueToTree(goodEnvelope),
                NOW.plusMillis(1)));

        outboxPublisher.publishPendingEvents();

        OutboxEvent bad = findByEventId(badEventId);
        assertThat(bad.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(bad.getAttempts()).isEqualTo(1);
        assertThat(bad.getPublishedAt()).isNull();
        OutboxEvent good = findByEventId(goodEventId);
        assertThat(good.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(good.getAttempts()).isZero();

        // Lần chạy sau vẫn thử lại dòng lỗi và tăng attempts, không kẹt batch.
        outboxPublisher.publishPendingEvents();
        assertThat(findByEventId(badEventId).getAttempts()).isEqualTo(2);
        assertThat(findByEventId(badEventId).getStatus()).isEqualTo(OutboxStatus.PENDING);

        Message message = receive(MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId())
                .isEqualTo(goodEventId.toString());
        assertThat(messageCount(MessagingTopology.INVENTORY_COMMANDS_QUEUE)).isZero();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private OutboxEvent findByEventId(UUID eventId) {
        return repository.findAll().stream()
                .filter(row -> row.getEventId().equals(eventId))
                .findFirst()
                .orElseThrow();
    }

    private Message receive(String queue) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Message message = rabbitTemplate.receive(queue, 500);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    private String body(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
