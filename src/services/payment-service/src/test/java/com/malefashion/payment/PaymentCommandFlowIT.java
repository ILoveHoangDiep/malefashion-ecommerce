package com.malefashion.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.payment.application.MockScenarioResolver;
import com.malefashion.payment.domain.model.Payment;
import com.malefashion.payment.infrastructure.messaging.MessageTypes;
import com.malefashion.payment.infrastructure.messaging.MessagingTopology;
import com.malefashion.payment.infrastructure.messaging.command.CommandMoney;
import com.malefashion.payment.infrastructure.messaging.command.ProcessPaymentPayload;
import com.malefashion.payment.infrastructure.messaging.command.RefundPaymentPayload;
import com.malefashion.payment.support.MessagingIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * Command thật đi qua {@code malefashion.commands} → listener → nghiệp vụ → outbox → broker.
 */
@TestPropertySource(properties = "payment.mock.honor-scenario=true")
class PaymentCommandFlowIT extends MessagingIntegrationTestBase {

    private static final CommandMoney AMOUNT = new CommandMoney("578000.00", "VND");

    @MockitoSpyBean
    private MockScenarioResolver scenarioResolverSpy;

    @Test
    void processPaymentSuccessEmitsSucceededEvent() {
        UUID orderId = UUID.randomUUID();
        UUID commandEventId = UUID.randomUUID();

        publishCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, commandEventId, orderId,
                processPayload(orderId, "SUCCESS"));

        Message message = awaitPublishedEvent(MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId);
        JsonNode envelope = envelopeOf(message);
        JsonNode payload = envelope.path("payload");

        assertAmqpProperties(message, envelope, orderId);
        assertThat(envelope.path("eventId").asText()).isNotEqualTo(commandEventId.toString());
        assertThat(envelope.path("producer").asText()).isEqualTo("payment-service");
        assertThat(envelope.path("eventVersion").asInt()).isEqualTo(1);
        assertContractInstant(envelope.path("occurredAt").asText());
        assertContractInstant(payload.path("paidAt").asText());
        assertThat(payload.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(payload.path("amount").path("amount").asText()).isEqualTo("578000.00");
        assertThat(payload.path("amount").path("currency").asText()).isEqualTo("VND");
        assertThat(payload.path("providerTransactionId").asText()).startsWith("MOCK-TXN-");

        assertThat(processedMessageCount(commandEventId)).isEqualTo(1);
        assertThat(outboxEvents(MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId)).hasSize(1);
    }

    @Test
    void processPaymentFailureEmitsFailedEvent() {
        UUID orderId = UUID.randomUUID();

        publishCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, UUID.randomUUID(), orderId,
                processPayload(orderId, "FAILURE"));

        Message message = awaitPublishedEvent(MessageTypes.EVENT_PAYMENT_FAILED, orderId);
        JsonNode payload = envelopeOf(message).path("payload");

        assertContractInstant(payload.path("failedAt").asText());
        assertThat(payload.path("failureCode").asText()).isEqualTo("PAYMENT_DECLINED");
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus().name())
                .isEqualTo("FAILED");
    }

    @Test
    void processPaymentTimeoutEmitsTimeoutFailureEvent() {
        UUID orderId = UUID.randomUUID();

        publishCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, UUID.randomUUID(), orderId,
                processPayload(orderId, "TIMEOUT"));

        Message message = awaitPublishedEvent(MessageTypes.EVENT_PAYMENT_FAILED, orderId);
        JsonNode payload = envelopeOf(message).path("payload");

        assertContractInstant(payload.path("failedAt").asText());
        assertThat(payload.path("failureCode").asText()).isEqualTo("PAYMENT_TIMEOUT");
    }

    @Test
    void duplicateCommandEventIdIsProcessedOnce() {
        UUID orderId = UUID.randomUUID();
        UUID commandEventId = UUID.randomUUID();
        ProcessPaymentPayload payload = processPayload(orderId, "SUCCESS");

        publishCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, commandEventId, orderId, payload);
        publishCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, commandEventId, orderId, payload);

        awaitOutboxEvent(MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId);
        awaitQueueEmpty(MessagingTopology.PAYMENT_COMMANDS_QUEUE);
        // Chờ thêm để chắc chắn message thứ hai đã được xử lý (và bị dedupe) chứ không chỉ đang chờ.
        assertNoEvent(MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId, Duration.ofMillis(500));

        assertThat(processedMessageCount(commandEventId)).isEqualTo(1);
        assertThat(outboxEvents(MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId)).hasSize(1);
        assertThat(countPayments()).isEqualTo(1);
    }

    @Test
    void replayForSameOrderUsesSamePaymentButNewEventId() {
        UUID orderId = UUID.randomUUID();
        ProcessPaymentPayload payload = processPayload(orderId, "SUCCESS");

        publishCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, UUID.randomUUID(), orderId, payload);
        JsonNode first = envelopeOf(awaitPublishedEvent(
                MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId));

        publishCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, UUID.randomUUID(), orderId, payload);
        // Event đầu đã được tiêu thụ; chờ nghiệp vụ replay ghi event thứ hai rồi mới publish.
        awaitOutboxEvents(MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId, 2);
        outboxPublisher.publishPendingEvents();
        JsonNode second = envelopeOf(receiveEvent(
                MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId, Duration.ofSeconds(20)));

        assertThat(second.path("eventId").asText())
                .isNotEqualTo(first.path("eventId").asText());
        assertThat(second.path("payload").path("paymentId").asText())
                .isEqualTo(first.path("payload").path("paymentId").asText());
        assertThat(second.path("payload").path("providerTransactionId").asText())
                .isEqualTo(first.path("payload").path("providerTransactionId").asText());
        assertThat(second.path("payload").path("paidAt").asText())
                .isEqualTo(first.path("payload").path("paidAt").asText());
        assertThat(countPayments()).isEqualTo(1);
    }

    @Test
    void refundEmitsRefundedEventAndReplaysSameRefund() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = createSucceededPayment(orderId);
        RefundPaymentPayload refund = new RefundPaymentPayload(
                orderId, paymentId, AMOUNT, "STOCK_COMMIT_FAILED");

        publishCommand(MessageTypes.COMMAND_REFUND_PAYMENT, UUID.randomUUID(), orderId, refund);
        JsonNode first = envelopeOf(awaitPublishedEvent(
                MessageTypes.EVENT_PAYMENT_REFUNDED, orderId));

        assertContractInstant(first.path("payload").path("refundedAt").asText());
        assertThat(first.path("payload").path("paymentId").asText())
                .isEqualTo(paymentId.toString());
        assertThat(first.path("payload").path("amount").path("amount").asText())
                .isEqualTo("578000.00");

        publishCommand(MessageTypes.COMMAND_REFUND_PAYMENT, UUID.randomUUID(), orderId, refund);
        awaitOutboxEvents(MessageTypes.EVENT_PAYMENT_REFUNDED, orderId, 2);
        outboxPublisher.publishPendingEvents();
        JsonNode second = envelopeOf(receiveEvent(
                MessageTypes.EVENT_PAYMENT_REFUNDED, orderId, Duration.ofSeconds(20)));

        assertThat(second.path("eventId").asText())
                .isNotEqualTo(first.path("eventId").asText());
        assertThat(second.path("payload").path("refundId").asText())
                .isEqualTo(first.path("payload").path("refundId").asText());
        assertThat(second.path("payload").path("refundedAt").asText())
                .isEqualTo(first.path("payload").path("refundedAt").asText());
        assertThat(countRefunds()).isEqualTo(1);
    }

    @Test
    void refundForUnknownPaymentProducesNoEvent() {
        UUID orderId = UUID.randomUUID();
        UUID commandEventId = UUID.randomUUID();

        publishCommand(MessageTypes.COMMAND_REFUND_PAYMENT, commandEventId, orderId,
                new RefundPaymentPayload(orderId, UUID.randomUUID(), AMOUNT, "STOCK_COMMIT_FAILED"));

        awaitProcessed(commandEventId);
        assertNoEvent(MessageTypes.EVENT_PAYMENT_REFUNDED, orderId, Duration.ofMillis(1000));

        assertThat(countOutboxEvents()).isZero();
        assertThat(countPayments()).isZero();
        assertThat(countRefunds()).isZero();
    }

    @Test
    void silentTimeoutAcksCommandButProducesNoEvent() {
        doReturn(true).when(scenarioResolverSpy).isSilentOnTimeout();
        UUID orderId = UUID.randomUUID();
        UUID commandEventId = UUID.randomUUID();

        publishCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, commandEventId, orderId,
                processPayload(orderId, "TIMEOUT"));

        awaitProcessed(commandEventId);
        assertNoEvent(MessageTypes.EVENT_PAYMENT_FAILED, orderId, Duration.ofMillis(1000));

        assertThat(countOutboxEvents()).isZero();
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus().name()).isEqualTo("FAILED");
        assertThat(payment.getFailureCode()).isEqualTo("PAYMENT_TIMEOUT");
    }

    private UUID createSucceededPayment(UUID orderId) {
        publishCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, UUID.randomUUID(), orderId,
                processPayload(orderId, "SUCCESS"));
        JsonNode succeeded = envelopeOf(awaitPublishedEvent(
                MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId));
        return UUID.fromString(succeeded.path("payload").path("paymentId").asText());
    }

    private ProcessPaymentPayload processPayload(UUID orderId, String scenario) {
        return new ProcessPaymentPayload(orderId, AMOUNT, orderId.toString(), scenario);
    }

    private void awaitProcessed(UUID commandEventId) {
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> processedMessageCount(commandEventId) == 1);
    }

    private void assertAmqpProperties(Message message, JsonNode envelope, UUID orderId) {
        MessageProperties properties = message.getMessageProperties();
        assertThat(properties.getMessageId())
                .isEqualTo(envelope.path("eventId").asText());
        assertThat(properties.getType()).isEqualTo(MessageTypes.EVENT_PAYMENT_SUCCEEDED);
        assertThat(properties.getCorrelationId()).isEqualTo(orderId.toString());
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getReceivedDeliveryMode()).isEqualTo(
                org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        assertThat(properties.getHeaders().get("x-event-version")).isEqualTo(1);
    }
}
