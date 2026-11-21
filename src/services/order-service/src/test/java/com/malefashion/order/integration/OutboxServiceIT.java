package com.malefashion.order.integration;

import com.malefashion.order.application.OutboxService;
import com.malefashion.order.infrastructure.messaging.MessageEnvelope;
import com.malefashion.order.infrastructure.messaging.MessageTypes;
import com.malefashion.order.infrastructure.messaging.MessagingTopology;
import com.malefashion.order.infrastructure.messaging.OrderMessageFactory;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.infrastructure.persistence.OutboxEventRepository;
import com.malefashion.order.infrastructure.persistence.OutboxStatus;
import com.malefashion.order.support.ContractSchemas;
import com.malefashion.order.support.IntegrationTestBase;
import com.malefashion.order.support.OrderFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OutboxService ghi đúng một dòng mỗi lần enqueue, trong transaction của caller. event_id không
 * unique: cùng envelope gọi hai lần tạo hai dòng khác id.
 */
class OutboxServiceIT extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-10-22T03:15:30.123456Z");

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OrderMessageFactory orderMessageFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void appendCommandWritesFullEnvelopeAsPendingRow() {
        UUID eventId = UUID.randomUUID();
        MessageEnvelope<OrderMessageFactory.ReserveStockPayload> envelope =
                orderMessageFactory.reserveStock(eventId,
                        OrderFixtures.newOrder(NOW.truncatedTo(ChronoUnit.MILLIS)));

        OutboxEvent row = inTransaction(() -> outboxService.appendCommand(envelope));

        assertThat(row.getId()).isNotEqualTo(eventId);
        assertThat(row.getEventId()).isEqualTo(eventId);
        assertThat(row.getEventType()).isEqualTo(MessageTypes.COMMAND_RESERVE_STOCK);
        assertThat(row.getAggregateId()).isEqualTo(OrderFixtures.ORDER_ID);
        assertThat(row.getExchange()).isEqualTo(MessagingTopology.COMMANDS_EXCHANGE);
        assertThat(row.getRoutingKey()).isEqualTo(MessageTypes.COMMAND_RESERVE_STOCK);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getPayload().path("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(row.getPayload().path("payload").path("orderId").asText())
                .isEqualTo(OrderFixtures.ORDER_ID.toString());

        String stored = storedPayload(row.getEventId());
        ContractSchemas.validate(MessageTypes.COMMAND_RESERVE_STOCK, stored);
    }

    @Test
    void appendEventUsesEventsExchangeAndValidContractPayload() {
        MessageEnvelope<OrderMessageFactory.OrderCreatedPayload> envelope =
                orderMessageFactory.orderCreated(
                        OrderFixtures.newOrder(NOW.truncatedTo(ChronoUnit.MILLIS)));

        OutboxEvent row = inTransaction(() -> outboxService.appendEvent(envelope));

        assertThat(row.getExchange()).isEqualTo(MessagingTopology.EVENTS_EXCHANGE);
        assertThat(row.getRoutingKey()).isEqualTo(MessageTypes.EVENT_ORDER_CREATED);
        assertThat(row.getEventId()).isEqualTo(envelope.eventId());
        ContractSchemas.validate(MessageTypes.EVENT_ORDER_CREATED,
                storedPayload(row.getEventId()));
    }

    @Test
    void duplicateEventIdCreatesTwoRowsWithDistinctRowIds() {
        UUID eventId = UUID.randomUUID();
        MessageEnvelope<OrderMessageFactory.CommitStockPayload> envelope =
                orderMessageFactory.commitStock(eventId, OrderFixtures.ORDER_ID,
                        OrderFixtures.RESERVATION_ID);

        inTransaction(() -> {
            outboxService.appendCommand(envelope);
            outboxService.appendCommand(envelope);
            return null;
        });

        assertThat(outboxEventRepository.findAll())
                .hasSize(2)
                .extracting(OutboxEvent::getEventId)
                .containsOnly(eventId);
        assertThat(outboxEventRepository.findAll())
                .extracting(OutboxEvent::getId)
                .doesNotHaveDuplicates();
    }

    @Test
    void refusesToRunWithoutCallerTransaction() {
        MessageEnvelope<OrderMessageFactory.OrderCreatedPayload> envelope =
                orderMessageFactory.orderCreated(
                        OrderFixtures.newOrder(NOW.truncatedTo(ChronoUnit.MILLIS)));

        assertThatThrownBy(() -> outboxService.appendEvent(envelope))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void rollsBackWithCallerTransaction() {
        MessageEnvelope<OrderMessageFactory.OrderCreatedPayload> envelope =
                orderMessageFactory.orderCreated(
                        OrderFixtures.newOrder(NOW.truncatedTo(ChronoUnit.MILLIS)));

        assertThatThrownBy(() -> inTransaction(() -> {
            outboxService.appendEvent(envelope);
            throw new IllegalStateException("simulated failure after enqueue");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outboxEventRepository.count()).isZero();
    }

    private <T> T inTransaction(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(status -> action.get());
    }

    private String storedPayload(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE event_id = ?", String.class,
                eventId);
    }
}
