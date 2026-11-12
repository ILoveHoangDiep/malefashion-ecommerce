package com.malefashion.payment;

import com.malefashion.payment.infrastructure.messaging.MessageTypes;
import com.malefashion.payment.infrastructure.messaging.MessagingTopology;
import com.malefashion.payment.infrastructure.messaging.envelope.MessageEnvelope;
import com.malefashion.payment.infrastructure.messaging.event.PaymentEventFactory;
import com.malefashion.payment.infrastructure.persistence.OutboxEvent;
import com.malefashion.payment.infrastructure.persistence.OutboxStatus;
import com.malefashion.payment.support.MessagingIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publisher chỉ đánh dấu SENT sau khi broker ack và không trả message (mandatory + returns).
 */
class PaymentOutboxPublisherIT extends MessagingIntegrationTestBase {

    private static final BigDecimal AMOUNT = new BigDecimal("578000.00");

    @Autowired
    private PaymentEventFactory paymentEventFactory;

    @Autowired
    private AmqpAdmin amqpAdmin;

    private String testQueue;

    @AfterEach
    void deleteTestQueue() {
        if (testQueue != null) {
            amqpAdmin.deleteQueue(testQueue);
            testQueue = null;
        }
    }

    @Test
    void unroutableEventStaysPendingUntilBindingExists() {
        UUID orderId = UUID.randomUUID();
        String routingKey = "payment.outbox.unroutable." + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        savePending(eventId, MessageTypes.EVENT_PAYMENT_SUCCEEDED, routingKey,
                paymentEventFactory.paymentSucceeded(orderId, UUID.randomUUID(), AMOUNT,
                        "MOCK-TXN-20261105-000001", Instant.now(), TRACE_ID));

        outboxPublisher.publishPendingEvents();

        OutboxEvent afterFailure = reload(eventId);
        assertThat(afterFailure.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFailure.getPublishedAt()).isNull();

        testQueue = "payment-service.publisher-test." + UUID.randomUUID();
        amqpAdmin.declareQueue(new Queue(testQueue, false, false, true));
        amqpAdmin.declareBinding(BindingBuilder
                .bind(new Queue(testQueue, false, false, true))
                .to(new TopicExchange(MessagingTopology.EVENTS_EXCHANGE))
                .with(routingKey));

        outboxPublisher.publishPendingEvents();

        OutboxEvent afterSuccess = reload(eventId);
        assertThat(afterSuccess.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(afterSuccess.getAttempts()).isEqualTo(1);
        assertThat(afterSuccess.getPublishedAt()).isNotNull();

        Message message = rabbitTemplate.receive(testQueue, 5000);
        assertThat(message).isNotNull();
        assertAmqpProperties(message, eventId, MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId);
        assertContractInstant(envelopeOf(message).path("occurredAt").asText());
    }

    @Test
    void publishesSucceededToOrderServiceSagaQueue() {
        assertPublishedToSagaQueue(MessageTypes.EVENT_PAYMENT_SUCCEEDED, "paidAt");
    }

    @Test
    void publishesFailedToOrderServiceSagaQueue() {
        assertPublishedToSagaQueue(MessageTypes.EVENT_PAYMENT_FAILED, "failedAt");
    }

    @Test
    void publishesRefundedToOrderServiceSagaQueue() {
        assertPublishedToSagaQueue(MessageTypes.EVENT_PAYMENT_REFUNDED, "refundedAt");
    }

    private void assertPublishedToSagaQueue(String eventType, String instantField) {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MessageEnvelope<?> envelope = switch (eventType) {
            case MessageTypes.EVENT_PAYMENT_SUCCEEDED -> paymentEventFactory.paymentSucceeded(
                    orderId, paymentId, AMOUNT, "MOCK-TXN-20261105-000002",
                    Instant.now(), TRACE_ID);
            case MessageTypes.EVENT_PAYMENT_FAILED -> paymentEventFactory.paymentFailed(
                    orderId, paymentId, AMOUNT, "PAYMENT_DECLINED", "Mock payment declined",
                    Instant.now(), TRACE_ID);
            case MessageTypes.EVENT_PAYMENT_REFUNDED -> paymentEventFactory.paymentRefunded(
                    orderId, paymentId, UUID.randomUUID(), AMOUNT, Instant.now(), TRACE_ID);
            default -> throw new IllegalArgumentException(eventType);
        };
        savePending(eventId, eventType, eventType, envelope);

        outboxPublisher.publishPendingEvents();

        assertThat(reload(eventId).getStatus()).isEqualTo(OutboxStatus.SENT);
        // receiveEvent validate payload bằng JSON Schema sống trong contracts/events.
        Message message = receiveEvent(eventType, orderId, Duration.ofSeconds(20));
        assertAmqpProperties(message, eventId, eventType, orderId);
        assertContractInstant(envelopeOf(message).path("occurredAt").asText());
        assertContractInstant(
                envelopeOf(message).path("payload").path(instantField).asText());
    }

    private void savePending(UUID eventId, String eventType, String routingKey,
                             MessageEnvelope<?> envelope) {
        outboxEventRepository.save(OutboxEvent.pending(
                eventId,
                eventType,
                routingKey,
                objectMapper.valueToTree(envelope),
                Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    }

    private OutboxEvent reload(UUID eventId) {
        return outboxEventRepository.findById(eventId).orElseThrow();
    }

    private void assertAmqpProperties(Message message, UUID eventId, String eventType,
                                      UUID orderId) {
        MessageProperties properties = message.getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(eventId.toString());
        assertThat(properties.getType()).isEqualTo(eventType);
        assertThat(properties.getCorrelationId()).isEqualTo(orderId.toString());
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.getHeaders().get("x-event-version")).isEqualTo(1);
    }
}
