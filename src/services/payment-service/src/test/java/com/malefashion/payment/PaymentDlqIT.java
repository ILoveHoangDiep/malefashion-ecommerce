package com.malefashion.payment;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.malefashion.payment.application.ProcessPaymentService;
import com.malefashion.payment.infrastructure.messaging.MessageTypes;
import com.malefashion.payment.infrastructure.messaging.MessagingTopology;
import com.malefashion.payment.infrastructure.messaging.command.CommandMoney;
import com.malefashion.payment.infrastructure.messaging.command.ProcessPaymentPayload;
import com.malefashion.payment.infrastructure.messaging.envelope.MessageEnvelope;
import com.malefashion.payment.support.MessagingIntegrationTestBase;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Lỗi vĩnh viễn → DLQ ngay (không retry). Lỗi tạm thời → đúng 3 lần thử rồi DLQ.
 */
class PaymentDlqIT extends MessagingIntegrationTestBase {

    private static final CommandMoney AMOUNT = new CommandMoney("578000.00", "VND");

    @MockitoSpyBean
    private ProcessPaymentService processPaymentServiceSpy;

    @Test
    void malformedJsonIsDeadLetteredWithoutRetry() {
        UUID eventId = UUID.randomUUID();

        sendRawCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, eventId, "{not valid json");

        awaitDeadLettered();
        assertThat(processedMessageCount(eventId)).isZero();
        assertThat(countPayments()).isZero();
        verify(processPaymentServiceSpy, never()).process(any(), anyString());
        assertNoFurtherDelivery();
    }

    @Test
    void headerTypeMismatchIsDeadLetteredWithoutRetry() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ObjectNode envelope = validEnvelope(eventId, MessageTypes.COMMAND_PROCESS_PAYMENT, orderId,
                processPayload(orderId));
        envelope.put("eventType", MessageTypes.COMMAND_REFUND_PAYMENT);

        // Routing key theo header, body lại khai báo eventType khác.
        sendRawCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, MessageTypes.COMMAND_PROCESS_PAYMENT,
                eventId, json(envelope));

        awaitDeadLettered();
        assertThat(processedMessageCount(eventId)).isZero();
        verify(processPaymentServiceSpy, never()).process(any(), anyString());
        assertNoFurtherDelivery();
    }

    @Test
    void missingEnvelopeFieldIsDeadLetteredWithoutRetry() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ObjectNode envelope = validEnvelope(eventId, MessageTypes.COMMAND_PROCESS_PAYMENT, orderId,
                processPayload(orderId));
        envelope.remove("correlationId");

        sendRawCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, eventId, json(envelope));

        awaitDeadLettered();
        assertThat(processedMessageCount(eventId)).isZero();
        verify(processPaymentServiceSpy, never()).process(any(), anyString());
        assertNoFurtherDelivery();
    }

    @Test
    void invalidPayloadIsDeadLetteredAfterOneAttempt() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("amount", objectMapper.valueToTree(AMOUNT));
        payload.put("idempotencyKey", orderId.toString());
        ObjectNode envelope = validEnvelope(eventId, MessageTypes.COMMAND_PROCESS_PAYMENT, orderId,
                payload);

        sendRawCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, eventId, json(envelope));

        awaitDeadLettered();
        verify(processPaymentServiceSpy, times(1)).process(any(), anyString());
        assertThat(processedMessageCount(eventId)).isZero();
        assertThat(countPayments()).isZero();
        assertNoFurtherDelivery();
    }

    @Test
    void unsupportedCommandTypeIsDeadLetteredWithoutRetry() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ObjectNode envelope = validEnvelope(eventId, MessageTypes.COMMAND_PROCESS_PAYMENT, orderId,
                processPayload(orderId));
        envelope.put("eventType", "payment.not-a-command");

        // Message vẫn vào được queue nhờ routing key hợp lệ; type header/body là loại không hỗ trợ.
        sendRawCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, "payment.not-a-command", eventId,
                json(envelope));

        awaitDeadLettered();
        assertThat(processedMessageCount(eventId)).isZero();
        verify(processPaymentServiceSpy, never()).process(any(), anyString());
        assertNoFurtherDelivery();
    }

    @Test
    void temporaryFailureIsRetriedThreeTimesThenDeadLettered() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        doThrow(new DataAccessResourceFailureException("simulated database outage"))
                .when(processPaymentServiceSpy).process(any(), any());

        publishCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, eventId, orderId,
                processPayload(orderId));

        awaitDeadLettered();
        verify(processPaymentServiceSpy, times(3)).process(any(), any());
        // Marker dedupe rollback cùng nghiệp vụ nên không có dấu vết nào được commit.
        assertThat(processedMessageCount(eventId)).isZero();
        assertThat(countPayments()).isZero();
        assertThat(countOutboxEvents()).isZero();
        assertNoFurtherDelivery();
    }

    private void awaitDeadLettered() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> messageCount(MessagingTopology.PAYMENT_COMMANDS_DLQ) == 1);
        awaitQueueEmpty(MessagingTopology.PAYMENT_COMMANDS_QUEUE);
    }

    private void assertNoFurtherDelivery() {
        Awaitility.await()
                .during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(2))
                .until(() -> messageCount(MessagingTopology.PAYMENT_COMMANDS_DLQ) == 1);
        assertThat(messageCount(MessagingTopology.PAYMENT_COMMANDS_QUEUE)).isZero();
    }

    private ProcessPaymentPayload processPayload(UUID orderId) {
        return new ProcessPaymentPayload(orderId, AMOUNT, orderId.toString(), "SUCCESS");
    }

    private ObjectNode validEnvelope(UUID eventId, String eventType, UUID correlationId,
                                     Object payload) {
        MessageEnvelope<Object> envelope = new MessageEnvelope<>(
                eventId, eventType, 1, Instant.now().truncatedTo(ChronoUnit.MILLIS),
                "order-service", correlationId, TRACE_ID, payload);
        return objectMapper.valueToTree(envelope);
    }

    private String json(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot serialize test envelope", failure);
        }
    }
}
