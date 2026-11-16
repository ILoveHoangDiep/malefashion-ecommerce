package com.malefashion.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.payment.application.command.ProcessPaymentCommand;
import com.malefashion.payment.application.command.RefundPaymentCommand;
import com.malefashion.payment.domain.model.Payment;
import com.malefashion.payment.domain.model.PaymentScenario;
import com.malefashion.payment.domain.model.PaymentStatus;
import com.malefashion.payment.infrastructure.messaging.MessageTypes;
import com.malefashion.payment.support.ContractSchemas;
import com.malefashion.payment.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * process-payment trên PostgreSQL thật: trạng thái payment, outbox cùng transaction, replay và
 * validate payload theo JSON Schema trong {@code contracts/events}.
 */
@TestPropertySource(properties = "payment.mock.honor-scenario=true")
class ProcessPaymentServiceIT extends IntegrationTestBase {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    private ProcessPaymentService processPaymentService;

    @Autowired
    private RefundPaymentService refundPaymentService;

    @Test
    void successScenarioPersistsSucceededPaymentAndEmitsContractEvent() throws Exception {
        UUID orderId = UUID.randomUUID();

        processPaymentService.process(command(orderId, "578000.00", "SUCCESS"), TRACE_ID);

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getScenario()).isEqualTo(PaymentScenario.SUCCESS);
        assertThat(payment.getAmount()).isEqualByComparingTo("578000.00");
        assertThat(payment.getCurrency()).isEqualTo("VND");
        assertThat(payment.getProviderTransactionId()).matches("^MOCK-TXN-\\d{8}-\\d{6,}$");
        assertThat(payment.getPaidAt()).isNotNull();
        assertThat(payment.getFailedAt()).isNull();
        assertThat(countPayments()).isEqualTo(1);

        List<String> payloads = storedOutboxPayloads();
        assertThat(payloads).hasSize(1);
        String json = payloads.getFirst();
        ContractSchemas.validateEvent(MessageTypes.EVENT_PAYMENT_SUCCEEDED, json);

        JsonNode event = objectMapper.readTree(json);
        assertThat(event.path("eventType").asText()).isEqualTo("payment.succeeded");
        assertThat(event.path("correlationId").asText()).isEqualTo(orderId.toString());
        assertThat(event.path("traceId").asText()).isEqualTo(TRACE_ID);
        assertThat(event.path("payload").path("paymentId").asText()).isEqualTo(payment.getId().toString());
        assertThat(event.path("payload").path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(event.path("payload").path("providerTransactionId").asText())
                .isEqualTo(payment.getProviderTransactionId());
        assertThat(event.path("payload").path("amount").path("amount").asText()).isEqualTo("578000.00");
    }

    @Test
    void failureScenarioPersistsFailedPaymentAndEmitsContractEvent() throws Exception {
        UUID orderId = UUID.randomUUID();

        processPaymentService.process(command(orderId, "578000.00", "FAILURE"), TRACE_ID);

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getScenario()).isEqualTo(PaymentScenario.FAILURE);
        assertThat(payment.getFailureCode()).isEqualTo("PAYMENT_DECLINED");
        assertThat(payment.getFailureMessage()).isEqualTo("Mock payment declined");
        assertThat(payment.getFailedAt()).isNotNull();
        assertThat(payment.getPaidAt()).isNull();
        assertThat(payment.getProviderTransactionId()).isNull();

        List<String> payloads = storedOutboxPayloads();
        assertThat(payloads).hasSize(1);
        ContractSchemas.validateEvent(MessageTypes.EVENT_PAYMENT_FAILED, payloads.getFirst());

        JsonNode event = objectMapper.readTree(payloads.getFirst());
        assertThat(event.path("eventType").asText()).isEqualTo("payment.failed");
        assertThat(event.path("payload").path("failureCode").asText()).isEqualTo("PAYMENT_DECLINED");
        assertThat(event.path("payload").path("failureMessage").asText()).isEqualTo("Mock payment declined");
        assertThat(event.path("payload").path("paymentId").asText()).isEqualTo(payment.getId().toString());
    }

    @Test
    void timeoutScenarioPersistsFailedPaymentAndEmitsContractEvent() throws Exception {
        UUID orderId = UUID.randomUUID();

        processPaymentService.process(command(orderId, "250000.00", "TIMEOUT"), TRACE_ID);

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getScenario()).isEqualTo(PaymentScenario.TIMEOUT);
        assertThat(payment.getFailureCode()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(payment.getFailureMessage()).isEqualTo("Mock payment timed out");

        List<String> payloads = storedOutboxPayloads();
        assertThat(payloads).hasSize(1);
        ContractSchemas.validateEvent(MessageTypes.EVENT_PAYMENT_FAILED, payloads.getFirst());
        JsonNode event = objectMapper.readTree(payloads.getFirst());
        assertThat(event.path("payload").path("failureCode").asText()).isEqualTo("PAYMENT_TIMEOUT");
    }

    @Test
    void duplicateOrderReplaysSucceededEventWithNewEventIdAndOldPaymentData() throws Exception {
        UUID orderId = UUID.randomUUID();
        processPaymentService.process(command(orderId, "578000.00", "SUCCESS"), TRACE_ID);
        Payment original = paymentRepository.findByOrderId(orderId).orElseThrow();
        String originalTransactionId = original.getProviderTransactionId();

        // Lệnh thứ hai lệch amount và lệch scenario: dữ liệu cũ vẫn được dùng cho event replay.
        processPaymentService.process(command(orderId, "999.00", "FAILURE"), TRACE_ID);

        assertThat(countPayments()).isEqualTo(1);
        Payment reloaded = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(original.getId());
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(reloaded.getAmount()).isEqualByComparingTo("578000.00");

        List<String> payloads = storedOutboxPayloads();
        assertThat(payloads).hasSize(2);

        JsonNode first = objectMapper.readTree(payloads.get(0));
        JsonNode replay = objectMapper.readTree(payloads.get(1));
        ContractSchemas.validateEvent(MessageTypes.EVENT_PAYMENT_SUCCEEDED, payloads.get(0));
        ContractSchemas.validateEvent(MessageTypes.EVENT_PAYMENT_SUCCEEDED, payloads.get(1));

        assertThat(replay.path("eventType").asText()).isEqualTo("payment.succeeded");
        assertThat(replay.path("eventId").asText()).isNotEqualTo(first.path("eventId").asText());
        assertThat(replay.path("payload").path("paymentId").asText()).isEqualTo(original.getId().toString());
        assertThat(replay.path("payload").path("providerTransactionId").asText())
                .isEqualTo(originalTransactionId);
        assertThat(replay.path("payload").path("paidAt").asText())
                .isEqualTo(first.path("payload").path("paidAt").asText());
        assertThat(replay.path("payload").path("amount").path("amount").asText()).isEqualTo("578000.00");
    }

    @Test
    void duplicateOrderReplaysFailedEventWithNewEventId() throws Exception {
        UUID orderId = UUID.randomUUID();
        processPaymentService.process(command(orderId, "578000.00", "FAILURE"), TRACE_ID);
        Payment original = paymentRepository.findByOrderId(orderId).orElseThrow();

        processPaymentService.process(command(orderId, "578000.00", "FAILURE"), TRACE_ID);

        assertThat(countPayments()).isEqualTo(1);
        List<String> payloads = storedOutboxPayloads();
        assertThat(payloads).hasSize(2);

        JsonNode first = objectMapper.readTree(payloads.get(0));
        JsonNode replay = objectMapper.readTree(payloads.get(1));
        ContractSchemas.validateEvent(MessageTypes.EVENT_PAYMENT_FAILED, payloads.get(1));

        assertThat(replay.path("eventType").asText()).isEqualTo("payment.failed");
        assertThat(replay.path("eventId").asText()).isNotEqualTo(first.path("eventId").asText());
        assertThat(replay.path("payload").path("paymentId").asText()).isEqualTo(original.getId().toString());
        assertThat(replay.path("payload").path("failureCode").asText()).isEqualTo("PAYMENT_DECLINED");
    }

    @Test
    void processPaymentOnRefundedPaymentEmitsNoEvent() throws Exception {
        UUID orderId = UUID.randomUUID();
        processPaymentService.process(command(orderId, "578000.00", "SUCCESS"), TRACE_ID);
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();

        RefundOutcome outcome = refundPaymentService.refund(new RefundPaymentCommand(
                orderId, payment.getId(), new BigDecimal("578000.00"), "VND", "STOCK_COMMIT_FAILED"), TRACE_ID);
        assertThat(outcome).isEqualTo(RefundOutcome.REFUNDED);
        assertThat(countOutboxEvents()).isEqualTo(2);

        processPaymentService.process(command(orderId, "578000.00", "SUCCESS"), TRACE_ID);

        assertThat(countOutboxEvents()).isEqualTo(2);
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        List<String> eventTypes = storedOutboxPayloads().stream()
                .map(this::eventTypeOf)
                .toList();
        assertThat(eventTypes).containsExactly("payment.succeeded", "payment.refunded");
    }

    @Test
    void rejectsNonPositiveAmountWithoutWritingAnything() {
        for (String amount : List.of("0.00", "-1.00")) {
            assertThatThrownBy(() -> processPaymentService.process(
                    command(UUID.randomUUID(), amount, "SUCCESS"), TRACE_ID))
                    .isInstanceOf(InvalidCommandException.class)
                    .hasMessageContaining("amount");
        }

        assertThat(countPayments()).isZero();
        assertThat(countOutboxEvents()).isZero();
    }

    @Test
    void rejectsCurrencyOtherThanVndWithoutWritingAnything() {
        UUID orderId = UUID.randomUUID();
        ProcessPaymentCommand command = new ProcessPaymentCommand(
                orderId, new BigDecimal("100000.00"), "USD", orderId.toString(), "SUCCESS");

        assertThatThrownBy(() -> processPaymentService.process(command, TRACE_ID))
                .isInstanceOf(InvalidCommandException.class)
                .hasMessageContaining("currency");

        assertThat(countPayments()).isZero();
        assertThat(countOutboxEvents()).isZero();
    }

    @Test
    void rejectsBlankIdempotencyKey() {
        UUID orderId = UUID.randomUUID();
        ProcessPaymentCommand command = new ProcessPaymentCommand(
                orderId, new BigDecimal("100000.00"), "VND", "  ", "SUCCESS");

        assertThatThrownBy(() -> processPaymentService.process(command, TRACE_ID))
                .isInstanceOf(InvalidCommandException.class)
                .hasMessageContaining("idempotencyKey");

        assertThat(countPayments()).isZero();
        assertThat(countOutboxEvents()).isZero();
    }

    private ProcessPaymentCommand command(UUID orderId, String amount, String scenario) {
        return new ProcessPaymentCommand(
                orderId, new BigDecimal(amount), "VND", orderId.toString(), scenario);
    }

    private String eventTypeOf(String json) {
        try {
            return objectMapper.readTree(json).path("eventType").asText();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
