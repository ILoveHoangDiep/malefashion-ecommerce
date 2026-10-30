package com.malefashion.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.inventory.infrastructure.messaging.MessageTypes;
import com.malefashion.inventory.infrastructure.messaging.MessagingTopology;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelopes;
import com.malefashion.inventory.infrastructure.messaging.event.StockReservedPayload;
import com.malefashion.inventory.infrastructure.persistence.OutboxEvent;
import com.malefashion.inventory.infrastructure.persistence.OutboxStatus;
import com.malefashion.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * Tests the outbox publisher's two outcomes: a failed publish/confirm leaves the row PENDING with an
 * incremented attempt counter, while a confirmed publish marks it SENT with a published timestamp.
 */
class OutboxPublisherIntegrationTest extends IntegrationTestBase {

    @MockitoSpyBean
    private RabbitTemplate rabbitTemplateSpy;

    @Test
    void failedPublishKeepsEventPendingAndIncrementsAttempts() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OutboxEvent event = pendingEvent(eventId, orderId, reservationId, now);
        outboxEventRepository.save(event);

        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplateSpy)
                .send(eq(MessagingTopology.EVENTS_EXCHANGE), eq(MessageTypes.EVENT_STOCK_RESERVED),
                        any(Message.class), any(CorrelationData.class));

        outboxPublisher.publishPendingEvents();

        OutboxEvent stored = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getPublishedAt()).isNull();
    }

    @Test
    void confirmedPublishMarksEventSentWithPublishedAt() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        outboxEventRepository.save(pendingEvent(eventId, orderId, reservationId, now));

        outboxPublisher.publishPendingEvents();

        OutboxEvent stored = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(stored.getAttempts()).isZero();
        assertThat(stored.getPublishedAt()).isNotNull();

        Message message = receiveEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId, Duration.ofSeconds(20));
        assertThat(envelopeOf(message).path("payload").path("reservationId").asText())
                .isEqualTo(reservationId.toString());
    }

    @Test
    void nackedConfirmKeepsEventPendingAndIncrementsAttempts() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        outboxEventRepository.save(pendingEvent(eventId, orderId, reservationId, now));

        // The broker never acks: complete the correlation with a negative confirm instead of sending.
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "simulated nack"));
            return null;
        }).when(rabbitTemplateSpy)
                .send(eq(MessagingTopology.EVENTS_EXCHANGE), eq(MessageTypes.EVENT_STOCK_RESERVED),
                        any(Message.class), any(CorrelationData.class));

        outboxPublisher.publishPendingEvents();

        OutboxEvent stored = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getPublishedAt()).isNull();

        assertNoEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId, Duration.ofSeconds(1));
    }

    @Test
    void unroutableEventStaysPendingAndIncrementsAttempts() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String unroutableKey = "inventory.no-binding." + UUID.randomUUID();
        outboxEventRepository.save(pendingEvent(eventId, orderId, reservationId, now, unroutableKey));

        outboxPublisher.publishPendingEvents();

        OutboxEvent stored = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getPublishedAt()).isNull();

        assertNoEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId, Duration.ofSeconds(1));
    }

    private OutboxEvent pendingEvent(UUID eventId, UUID orderId, UUID reservationId, Instant now) {
        return pendingEvent(eventId, orderId, reservationId, now, MessageTypes.EVENT_STOCK_RESERVED);
    }

    private OutboxEvent pendingEvent(UUID eventId, UUID orderId, UUID reservationId, Instant now,
                                     String routingKey) {
        StockReservedPayload payload = new StockReservedPayload(
                orderId,
                reservationId,
                now.plus(15, ChronoUnit.MINUTES),
                List.of(new StockReservedPayload.Item(UUID.randomUUID(), "MF-TEE-BLACK-M", 1)));
        JsonNode envelope = objectMapper.valueToTree(MessageEnvelopes.create(
                MessageTypes.EVENT_STOCK_RESERVED, orderId, TRACE_ID, payload));
        return OutboxEvent.pending(
                eventId, MessageTypes.EVENT_STOCK_RESERVED, routingKey, envelope, now);
    }
}
