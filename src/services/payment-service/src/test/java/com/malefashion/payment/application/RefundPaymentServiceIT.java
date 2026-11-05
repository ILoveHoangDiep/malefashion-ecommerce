package com.malefashion.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.payment.application.command.ProcessPaymentCommand;
import com.malefashion.payment.application.command.RefundPaymentCommand;
import com.malefashion.payment.domain.model.Payment;
import com.malefashion.payment.domain.model.PaymentStatus;
import com.malefashion.payment.domain.model.Refund;
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
 * refund-payment trên PostgreSQL thật: tạo refund, khóa payment, replay refundId cũ, và các trường
 * hợp NO_EVENT không được thay đổi dữ liệu.
 */
@TestPropertySource(properties = "payment.mock.honor-scenario=true")
class RefundPaymentServiceIT extends IntegrationTestBase {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    private ProcessPaymentService processPaymentService;

    @Autowired
    private RefundPaymentService refundPaymentService;

    @Test
    void refundsSucceededPaymentAndEmitsContractEvent() throws Exception {
        Payment payment = pay(UUID.randomUUID());

        RefundOutcome outcome = refundPaymentService.refund(refundCommand(
                payment.getOrderId(), payment.getId(), "578000.00"), TRACE_ID);

        assertThat(outcome).isEqualTo(RefundOutcome.REFUNDED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);

        Refund refund = refundRepository.findByPaymentId(payment.getId()).orElseThrow();
        assertThat(refund.getOrderId()).isEqualTo(payment.getOrderId());
        assertThat(refund.getAmount()).isEqualByComparingTo("578000.00");
        assertThat(refund.getReason()).isEqualTo("STOCK_COMMIT_FAILED");
        assertThat(refund.getRefundedAt()).isNotNull();
        assertThat(countRefunds()).isEqualTo(1);

        List<String> payloads = payloadsOfType(MessageTypes.EVENT_PAYMENT_REFUNDED);
        assertThat(payloads).hasSize(1);
        ContractSchemas.validateEvent(MessageTypes.EVENT_PAYMENT_REFUNDED, payloads.getFirst());

        JsonNode event = objectMapper.readTree(payloads.getFirst());
        assertThat(event.path("payload").path("paymentId").asText()).isEqualTo(payment.getId().toString());
        assertThat(event.path("payload").path("orderId").asText())
                .isEqualTo(payment.getOrderId().toString());
        assertThat(event.path("payload").path("refundId").asText()).isEqualTo(refund.getId().toString());
        assertThat(event.path("payload").path("amount").path("amount").asText()).isEqualTo("578000.00");
        assertThat(event.path("correlationId").asText()).isEqualTo(payment.getOrderId().toString());
    }

    @Test
    void secondRefundReplaysSameRefundIdWithNewEventId() throws Exception {
        Payment payment = pay(UUID.randomUUID());

        assertThat(refundPaymentService.refund(refundCommand(
                payment.getOrderId(), payment.getId(), "578000.00"), TRACE_ID))
                .isEqualTo(RefundOutcome.REFUNDED);
        assertThat(refundPaymentService.refund(refundCommand(
                payment.getOrderId(), payment.getId(), "578000.00"), TRACE_ID))
                .isEqualTo(RefundOutcome.EVENT_REPLAYED);

        assertThat(countRefunds()).isEqualTo(1);
        Refund refund = refundRepository.findByPaymentId(payment.getId()).orElseThrow();

        List<String> payloads = payloadsOfType(MessageTypes.EVENT_PAYMENT_REFUNDED);
        assertThat(payloads).hasSize(2);
        JsonNode first = objectMapper.readTree(payloads.get(0));
        JsonNode replay = objectMapper.readTree(payloads.get(1));
        ContractSchemas.validateEvent(MessageTypes.EVENT_PAYMENT_REFUNDED, payloads.get(1));

        assertThat(replay.path("eventId").asText()).isNotEqualTo(first.path("eventId").asText());
        assertThat(replay.path("payload").path("refundId").asText()).isEqualTo(refund.getId().toString());
        assertThat(replay.path("payload").path("refundedAt").asText())
                .isEqualTo(first.path("payload").path("refundedAt").asText());
        assertThat(replay.path("payload").path("amount").path("amount").asText()).isEqualTo("578000.00");
    }

    @Test
    void unknownOrderProducesNoEventAndNoRefund() {
        UUID orderId = UUID.randomUUID();

        RefundOutcome outcome = refundPaymentService.refund(
                refundCommand(orderId, UUID.randomUUID(), "578000.00"), TRACE_ID);

        assertThat(outcome).isEqualTo(RefundOutcome.NO_EVENT);
        assertThat(countRefunds()).isZero();
        assertThat(countOutboxEvents()).isZero();
        assertThat(countPayments()).isZero();
    }

    @Test
    void mismatchedPaymentIdProducesNoEventAndKeepsPaymentSucceeded() {
        Payment payment = pay(UUID.randomUUID());
        int outboxBefore = countOutboxEvents();

        RefundOutcome outcome = refundPaymentService.refund(
                refundCommand(payment.getOrderId(), UUID.randomUUID(), "578000.00"), TRACE_ID);

        assertThat(outcome).isEqualTo(RefundOutcome.NO_EVENT);
        assertThat(countRefunds()).isZero();
        assertThat(countOutboxEvents()).isEqualTo(outboxBefore);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void failedPaymentProducesNoEventAndNoRefund() {
        Payment payment = pay(UUID.randomUUID(), "FAILURE");
        int outboxBefore = countOutboxEvents();

        RefundOutcome outcome = refundPaymentService.refund(
                refundCommand(payment.getOrderId(), payment.getId(), "578000.00"), TRACE_ID);

        assertThat(outcome).isEqualTo(RefundOutcome.NO_EVENT);
        assertThat(countRefunds()).isZero();
        assertThat(countOutboxEvents()).isEqualTo(outboxBefore);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void amountMismatchStillRefundsTheStoredAmount() throws Exception {
        Payment payment = pay(UUID.randomUUID());

        RefundOutcome outcome = refundPaymentService.refund(refundCommand(
                payment.getOrderId(), payment.getId(), "1.00"), TRACE_ID);

        assertThat(outcome).isEqualTo(RefundOutcome.REFUNDED);
        Refund refund = refundRepository.findByPaymentId(payment.getId()).orElseThrow();
        assertThat(refund.getAmount()).isEqualByComparingTo("578000.00");

        List<String> payloads = payloadsOfType(MessageTypes.EVENT_PAYMENT_REFUNDED);
        assertThat(payloads).hasSize(1);
        ContractSchemas.validateEvent(MessageTypes.EVENT_PAYMENT_REFUNDED, payloads.getFirst());
        JsonNode event = objectMapper.readTree(payloads.getFirst());
        assertThat(event.path("payload").path("amount").path("amount").asText()).isEqualTo("578000.00");
    }

    @Test
    void rejectsReasonOtherThanStockCommitFailed() {
        Payment payment = pay(UUID.randomUUID());
        int outboxBefore = countOutboxEvents();

        RefundPaymentCommand command = new RefundPaymentCommand(
                payment.getOrderId(), payment.getId(), new BigDecimal("578000.00"), "VND", "CUSTOMER_REQUEST");

        assertThatThrownBy(() -> refundPaymentService.refund(command, TRACE_ID))
                .isInstanceOf(InvalidCommandException.class)
                .hasMessageContaining("reason");

        assertThat(countRefunds()).isZero();
        assertThat(countOutboxEvents()).isEqualTo(outboxBefore);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void rejectsNonPositiveRefundAmount() {
        Payment payment = pay(UUID.randomUUID());
        int outboxBefore = countOutboxEvents();

        assertThatThrownBy(() -> refundPaymentService.refund(refundCommand(
                payment.getOrderId(), payment.getId(), "0.00"), TRACE_ID))
                .isInstanceOf(InvalidCommandException.class)
                .hasMessageContaining("amount");

        assertThat(countRefunds()).isZero();
        assertThat(countOutboxEvents()).isEqualTo(outboxBefore);
    }

    private Payment pay(UUID orderId) {
        return pay(orderId, "SUCCESS");
    }

    private Payment pay(UUID orderId, String scenario) {
        processPaymentService.process(new ProcessPaymentCommand(
                orderId, new BigDecimal("578000.00"), "VND", orderId.toString(), scenario), TRACE_ID);
        return paymentRepository.findByOrderId(orderId).orElseThrow();
    }

    private static RefundPaymentCommand refundCommand(UUID orderId, UUID paymentId, String amount) {
        return new RefundPaymentCommand(
                orderId, paymentId, new BigDecimal(amount), "VND", "STOCK_COMMIT_FAILED");
    }

    private List<String> payloadsOfType(String eventType) {
        return storedOutboxPayloads().stream()
                .filter(json -> eventType.equals(eventTypeOf(json)))
                .toList();
    }

    private String eventTypeOf(String json) {
        try {
            return objectMapper.readTree(json).path("eventType").asText();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
