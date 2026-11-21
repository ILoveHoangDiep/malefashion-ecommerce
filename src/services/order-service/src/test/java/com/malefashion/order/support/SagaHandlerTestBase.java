package com.malefashion.order.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.malefashion.order.application.InboxProcessor;
import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.RefundState;
import com.malefashion.order.domain.model.SagaStep;
import com.malefashion.order.infrastructure.messaging.InboundEvent;
import com.malefashion.order.infrastructure.messaging.InboundEventParser;
import com.malefashion.order.infrastructure.messaging.MessagingTopology;
import com.malefashion.order.infrastructure.persistence.OrderRepository;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.infrastructure.persistence.OutboxEventRepository;
import com.malefashion.order.infrastructure.persistence.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nền cho test handler + deadline với PostgreSQL thật: order được dựng trong DB ở đúng trạng thái,
 * event lấy từ example của contract rồi đi qua đúng {@link InboundEventParser} và
 * {@link InboxProcessor#process(InboundEvent)} như listener thật.
 */
@Import(SagaTestClockConfig.class)
public abstract class SagaHandlerTestBase extends IntegrationTestBase {

    protected static final Instant NOW = SagaTestClockConfig.NOW;
    protected static final Duration SAGA_TIMEOUT = Duration.ofMinutes(10);
    protected static final Duration COMPENSATION_TIMEOUT = Duration.ofMinutes(2);
    protected static final Duration REFUND_BACKOFF = Duration.ofMinutes(1);
    protected static final Duration COMMIT_TIMEOUT = Duration.ofMinutes(1);
    protected static final Duration COMMIT_BACKOFF = Duration.ofSeconds(30);

    @Autowired
    protected InboxProcessor inboxProcessor;

    @Autowired
    protected InboundEventParser inboundEventParser;

    @Autowired
    protected OrderRepository orderRepository;

    @Autowired
    protected OutboxEventRepository outboxEventRepository;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setInstant(NOW);
    }

    protected JsonNode example(String schemaFile) {
        try {
            JsonNode node = ContractSchemas.firstExample(objectMapper, schemaFile);
            return node.deepCopy();
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read example of " + schemaFile, failure);
        }
    }

    protected InboundEvent inbound(String schemaFile, UUID eventId) {
        return inbound(example(schemaFile), eventId);
    }

    protected InboundEvent inbound(JsonNode envelope, UUID eventId) {
        ((ObjectNode) envelope).put("eventId", eventId.toString());
        try {
            MessageProperties properties = new MessageProperties();
            properties.setMessageId(eventId.toString());
            properties.setType(envelope.get("eventType").asText());
            Message message = new Message(objectMapper.writeValueAsBytes(envelope), properties);
            return inboundEventParser.parse(message);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize test event", failure);
        }
    }

    /**
     * Đổi order đích của event ở cả envelope và payload để parser vẫn chấp nhận.
     */
    protected void retarget(JsonNode envelope, UUID orderId) {
        ((ObjectNode) envelope).put("correlationId", orderId.toString());
        ((ObjectNode) envelope.get("payload")).put("orderId", orderId.toString());
    }

    protected void deliver(InboundEvent event) {
        inboxProcessor.process(event);
    }

    protected Order save(Order order) {
        return orderRepository.saveAndFlush(order);
    }

    protected Order reload() {
        return orderRepository.findWithItemsById(OrderFixtures.ORDER_ID).orElseThrow();
    }

    protected Order newOrder() {
        return OrderFixtures.newOrder(NOW, NOW.plus(SAGA_TIMEOUT));
    }

    protected Order awaitingPaymentOrder() {
        Order order = newOrder();
        order.applyReservation(OrderFixtures.RESERVATION_ID, NOW.plus(Duration.ofMinutes(15)),
                NOW);
        order.moveToStep(SagaStep.AWAITING_PAYMENT, NOW);
        return save(order);
    }

    protected Order awaitingCommitOrder() {
        Order order = newOrder();
        order.applyReservation(OrderFixtures.RESERVATION_ID, NOW.plus(Duration.ofMinutes(15)),
                NOW);
        order.applyPayment(OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID, NOW);
        order.startCommit(COMMIT_TIMEOUT, NOW, UUID.randomUUID());
        order.moveToStep(SagaStep.AWAITING_COMMIT, NOW);
        return save(order);
    }

    protected Order awaitingReleaseOrder(CancelReason reason) {
        Order order = awaitingPaymentOrder();
        order.setPendingCancelReason(reason, NOW);
        order.moveToStep(SagaStep.AWAITING_RELEASE, NOW);
        order.setCompensationDeadline(NOW.plus(COMPENSATION_TIMEOUT), NOW);
        order.clearSagaDeadline(NOW);
        return save(order);
    }

    protected Order timedOutOrder() {
        Order order = awaitingPaymentOrder();
        order.cancel(CancelReason.SAGA_TIMEOUT, NOW);
        order.clearSagaDeadline(NOW);
        order.moveToStep(SagaStep.DONE, NOW);
        return save(order);
    }

    protected Order awaitingRefundOrder() {
        Order order = newOrder();
        order.applyReservation(OrderFixtures.RESERVATION_ID, NOW.plus(Duration.ofMinutes(15)),
                NOW);
        order.applyPayment(OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID, NOW);
        order.setPendingCancelReason(CancelReason.STOCK_COMMIT_FAILED, NOW);
        order.moveToStep(SagaStep.AWAITING_REFUND, NOW);
        order.startRefund(COMPENSATION_TIMEOUT, NOW, UUID.randomUUID());
        return save(order);
    }

    protected Order commitUnconfirmedOrder() {
        Order order = awaitingCommitOrder();
        order.markCommitUnconfirmed(NOW);
        return save(order);
    }

    protected List<OutboxEvent> outboxEvents() {
        return outboxEventRepository.findAll();
    }

    protected List<OutboxEvent> outboxEvents(String eventType) {
        return outboxEvents().stream()
                .filter(event -> eventType.equals(event.getEventType()))
                .toList();
    }

    protected OutboxEvent singleOutbox(String eventType) {
        List<OutboxEvent> events = outboxEvents(eventType);
        assertThat(events).as("outbox rows of type %s", eventType).hasSize(1);
        return events.getFirst();
    }

    protected void assertNoOutbox() {
        assertThat(outboxEvents()).isEmpty();
    }

    protected JsonNode payloadOf(OutboxEvent event) {
        return event.getPayload().path("payload");
    }

    protected void assertCommand(OutboxEvent event) {
        assertThat(event.getExchange()).isEqualTo(MessagingTopology.COMMANDS_EXCHANGE);
        assertThat(event.getRoutingKey()).isEqualTo(event.getEventType());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getEventId()).isNotNull();
        assertContractValid(event);
    }

    protected void assertOrderEvent(OutboxEvent event) {
        assertThat(event.getExchange()).isEqualTo(MessagingTopology.EVENTS_EXCHANGE);
        assertThat(event.getRoutingKey()).isEqualTo(event.getEventType());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertContractValid(event);
    }

    protected void assertContractValid(OutboxEvent event) {
        try {
            ContractSchemas.validate(event.getEventType(),
                    objectMapper.writeValueAsString(event.getPayload()));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize outbox payload", failure);
        }
    }

    protected long markerCount(UUID eventId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE message_id = ?", Long.class,
                eventId);
        return count == null ? 0 : count;
    }

    protected void assertSingleMarker(UUID eventId) {
        assertThat(markerCount(eventId)).isEqualTo(1);
    }

}
