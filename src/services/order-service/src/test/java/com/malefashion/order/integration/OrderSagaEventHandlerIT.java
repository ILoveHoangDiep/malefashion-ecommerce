package com.malefashion.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.OrderStatus;
import com.malefashion.order.domain.model.RefundState;
import com.malefashion.order.domain.model.SagaStep;
import com.malefashion.order.infrastructure.messaging.InboundEvent;
import com.malefashion.order.infrastructure.messaging.MessageTypes;
import com.malefashion.order.infrastructure.messaging.PaymentFailureCode;
import com.malefashion.order.infrastructure.messaging.ReservationFailureReason;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.SagaHandlerTestBase;
import com.malefashion.order.support.TestOrderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C.1-C.10 + C.14 + các nhánh commit đến muộn (17c/17d) ở mức handler thật: event đi qua parser,
 * inbox, state machine và outbox trong cùng transaction PostgreSQL.
 */
class OrderSagaEventHandlerIT extends SagaHandlerTestBase {

    @Test
    void happyPathReservePayCommitWritesThreeContractMessages() {
        save(newOrder());
        UUID reservedEventId = UUID.randomUUID();
        deliver(inbound("inventory-stock-reserved.json", reservedEventId));

        Order afterReserve = reload();
        assertThat(afterReserve.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(afterReserve.getSagaStep()).isEqualTo(SagaStep.AWAITING_PAYMENT);
        assertThat(afterReserve.getReservationId()).isEqualTo(OrderFixtures.RESERVATION_ID);
        assertSingleMarker(reservedEventId);

        OutboxEvent payment = singleOutbox(MessageTypes.COMMAND_PROCESS_PAYMENT);
        assertCommand(payment);
        assertThat(payment.getAggregateId()).isEqualTo(OrderFixtures.ORDER_ID);
        assertThat(payloadOf(payment).path("amount").path("amount").asText())
                .isEqualTo("578000.00");
        assertThat(payloadOf(payment).path("idempotencyKey").asText())
                .isEqualTo(OrderFixtures.ORDER_ID.toString());
        assertThat(payloadOf(payment).path("mockScenario").isMissingNode()).isTrue();
        assertThat(payment.getPayload().path("traceId").asText())
                .isEqualTo(TestOrderProperties.TRACE_ID);

        UUID paidEventId = UUID.randomUUID();
        deliver(inbound("payment-succeeded.json", paidEventId));

        Order afterPayment = reload();
        assertThat(afterPayment.getSagaStep()).isEqualTo(SagaStep.AWAITING_COMMIT);
        assertThat(afterPayment.getPaymentId()).isEqualTo(OrderFixtures.PAYMENT_ID);
        assertThat(afterPayment.getPaidAmount()).isEqualByComparingTo(OrderFixtures.TOTAL);
        assertThat(afterPayment.getSagaDeadlineAt()).isNull();
        assertThat(afterPayment.getCommitCommandEventId()).isNotNull();
        assertThat(afterPayment.getCommitRetryCount()).isZero();
        assertThat(afterPayment.getCommitDeadlineAt()).isEqualTo(NOW.plus(COMMIT_TIMEOUT));
        assertThat(afterPayment.isCommitUnconfirmed()).isFalse();
        assertSingleMarker(paidEventId);

        OutboxEvent commit = singleOutbox(MessageTypes.COMMAND_COMMIT_STOCK);
        assertCommand(commit);
        assertThat(commit.getEventId()).isEqualTo(afterPayment.getCommitCommandEventId());
        assertThat(payloadOf(commit).path("reservationId").asText())
                .isEqualTo(OrderFixtures.RESERVATION_ID.toString());

        UUID committedEventId = UUID.randomUUID();
        deliver(inbound("inventory-stock-committed.json", committedEventId));

        Order confirmed = reload();
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(confirmed.getCommitDeadlineAt()).isNull();
        assertThat(confirmed.isCommitUnconfirmed()).isFalse();
        assertSingleMarker(committedEventId);

        OutboxEvent confirmedEvent = singleOutbox(MessageTypes.EVENT_ORDER_CONFIRMED);
        assertOrderEvent(confirmedEvent);
        assertThat(confirmedEvent.getAggregateId()).isEqualTo(OrderFixtures.ORDER_ID);
        assertThat(payloadOf(confirmedEvent).path("orderNumber").asText())
                .isEqualTo(OrderFixtures.ORDER_NUMBER);
        assertThat(outboxEvents()).hasSize(3);
    }

    @ParameterizedTest
    @EnumSource(ReservationFailureReason.class)
    void stockReservationFailedCancelsWithoutRelease(ReservationFailureReason reason) {
        save(newOrder());
        JsonNode envelope = example("inventory-stock-reservation-failed.json");
        ((ObjectNode) envelope.get("payload")).put("reason", reason.name());
        UUID eventId = UUID.randomUUID();

        deliver(inbound(envelope, eventId));

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.OUT_OF_STOCK);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getSagaDeadlineAt()).isNull();
        assertSingleMarker(eventId);

        OutboxEvent cancelled = singleOutbox(MessageTypes.EVENT_ORDER_CANCELLED);
        assertOrderEvent(cancelled);
        assertThat(payloadOf(cancelled).path("reason").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(outboxEvents(MessageTypes.COMMAND_RELEASE_STOCK)).isEmpty();
        assertThat(outboxEvents()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(PaymentFailureCode.class)
    void paymentFailedReleasesStockThenCancels(PaymentFailureCode code) {
        save(awaitingPaymentOrder());
        JsonNode failed = example("payment-failed.json");
        ((ObjectNode) failed.get("payload")).put("failureCode", code.name());

        deliver(inbound(failed, UUID.randomUUID()));

        Order releasing = reload();
        assertThat(releasing.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(releasing.getSagaStep()).isEqualTo(SagaStep.AWAITING_RELEASE);
        assertThat(releasing.getPendingCancelReason()).isEqualTo(CancelReason.PAYMENT_FAILED);
        assertThat(releasing.getCompensationDeadlineAt())
                .isEqualTo(NOW.plus(COMPENSATION_TIMEOUT));

        OutboxEvent release = singleOutbox(MessageTypes.COMMAND_RELEASE_STOCK);
        assertCommand(release);
        assertThat(payloadOf(release).path("reason").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(payloadOf(release).path("reservationId").asText())
                .isEqualTo(OrderFixtures.RESERVATION_ID.toString());

        deliver(inbound("inventory-stock-released.json", UUID.randomUUID()));

        Order cancelled = reload();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCancelReason()).isEqualTo(CancelReason.PAYMENT_FAILED);
        assertThat(cancelled.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(cancelled.getCompensationDeadlineAt()).isNull();

        OutboxEvent cancelledEvent = singleOutbox(MessageTypes.EVENT_ORDER_CANCELLED);
        assertOrderEvent(cancelledEvent);
        assertThat(payloadOf(cancelledEvent).path("reason").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(outboxEvents()).hasSize(2);
    }

    @Test
    void stockCommitFailedRequestsRefundThenRefundedCancelsOrder() {
        save(awaitingCommitOrder());

        deliver(inbound("inventory-stock-commit-failed.json", UUID.randomUUID()));

        Order refunding = reload();
        assertThat(refunding.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(refunding.getSagaStep()).isEqualTo(SagaStep.AWAITING_REFUND);
        assertThat(refunding.getPendingCancelReason())
                .isEqualTo(CancelReason.STOCK_COMMIT_FAILED);
        assertThat(refunding.getRefundState()).isEqualTo(RefundState.REQUESTED);
        assertThat(refunding.getRefundCommandEventId()).isNotNull();
        assertThat(refunding.getRefundRetryCount()).isZero();
        assertThat(refunding.getRefundDeadlineAt()).isEqualTo(NOW.plus(COMPENSATION_TIMEOUT));
        assertThat(refunding.getCommitDeadlineAt()).isNull();

        OutboxEvent refund = singleOutbox(MessageTypes.COMMAND_REFUND_PAYMENT);
        assertCommand(refund);
        assertThat(refund.getEventId()).isEqualTo(refunding.getRefundCommandEventId());
        assertThat(payloadOf(refund).path("paymentId").asText())
                .isEqualTo(OrderFixtures.PAYMENT_ID.toString());
        assertThat(payloadOf(refund).path("amount").path("amount").asText())
                .isEqualTo("578000.00");
        assertThat(payloadOf(refund).path("reason").asText()).isEqualTo("STOCK_COMMIT_FAILED");

        deliver(inbound("payment-refunded.json", UUID.randomUUID()));

        Order cancelled = reload();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCancelReason()).isEqualTo(CancelReason.STOCK_COMMIT_FAILED);
        assertThat(cancelled.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(cancelled.getRefundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(cancelled.getRefundId()).isEqualTo(OrderFixtures.REFUND_ID);
        assertThat(cancelled.getRefundDeadlineAt()).isNull();

        OutboxEvent cancelledEvent = singleOutbox(MessageTypes.EVENT_ORDER_CANCELLED);
        assertOrderEvent(cancelledEvent);
        assertThat(payloadOf(cancelledEvent).path("reason").asText())
                .isEqualTo("STOCK_COMMIT_FAILED");
        assertThat(outboxEvents()).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = SagaStep.class, names = {"AWAITING_PAYMENT", "AWAITING_COMMIT"})
    void stockReleasedByTtlIsRecordedWithoutStateChangeOrMessage(SagaStep step) {
        Order saved = step == SagaStep.AWAITING_PAYMENT
                ? awaitingPaymentOrder() : awaitingCommitOrder();
        JsonNode envelope = example("inventory-stock-released.json");
        ((ObjectNode) envelope.get("payload")).put("reason", "RESERVATION_EXPIRED");

        deliver(inbound(envelope, UUID.randomUUID()));

        Order unchanged = reload();
        assertThat(unchanged.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(unchanged.getSagaStep()).isEqualTo(step);
        assertThat(unchanged.getRefundState()).isEqualTo(RefundState.NONE);
        assertThat(unchanged.getSagaDeadlineAt()).isEqualTo(saved.getSagaDeadlineAt());
        assertThat(unchanged.getCommitDeadlineAt()).isEqualTo(saved.getCommitDeadlineAt());
        assertNoOutbox();
    }

    @Test
    void latePaymentAfterTimeoutRefundsWithoutCancelledEvent() {
        save(timedOutOrder());

        deliver(inbound("payment-succeeded.json", UUID.randomUUID()));

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getRefundState()).isEqualTo(RefundState.REQUESTED);
        assertThat(order.getPaymentId()).isEqualTo(OrderFixtures.PAYMENT_ID);
        assertThat(order.getRefundCommandEventId()).isNotNull();
        assertThat(order.getRefundDeadlineAt()).isEqualTo(NOW.plus(COMPENSATION_TIMEOUT));

        assertCommand(singleOutbox(MessageTypes.COMMAND_REFUND_PAYMENT));
        assertThat(outboxEvents()).hasSize(1);
    }

    @Test
    void latePaymentWhileStockReleaseIsPendingRefundsWithoutChangingStep() {
        save(awaitingReleaseOrder(CancelReason.SAGA_TIMEOUT));

        deliver(inbound("payment-succeeded.json", UUID.randomUUID()));

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_RELEASE);
        assertThat(order.getPendingCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);
        assertThat(order.getRefundState()).isEqualTo(RefundState.REQUESTED);
        assertThat(order.getRefundCommandEventId()).isNotNull();

        assertCommand(singleOutbox(MessageTypes.COMMAND_REFUND_PAYMENT));
        assertThat(outboxEvents()).hasSize(1);
    }

    @Test
    void lateReservationAfterTimeoutReleasesStockWithoutProcessingPayment() {
        save(timedOutOrder());

        deliver(inbound("inventory-stock-reserved.json", UUID.randomUUID()));

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getReservationId()).isEqualTo(OrderFixtures.RESERVATION_ID);

        OutboxEvent release = singleOutbox(MessageTypes.COMMAND_RELEASE_STOCK);
        assertCommand(release);
        assertThat(payloadOf(release).path("reason").asText()).isEqualTo("SAGA_TIMEOUT");
        assertThat(outboxEvents(MessageTypes.COMMAND_PROCESS_PAYMENT)).isEmpty();
        assertThat(outboxEvents()).hasSize(1);
    }

    @Test
    void duplicateEventIdIsProcessedExactlyOnce() {
        save(awaitingPaymentOrder());
        UUID eventId = UUID.randomUUID();
        InboundEvent event = inbound("payment-succeeded.json", eventId);

        deliver(event);
        deliver(event);

        Order order = reload();
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_COMMIT);
        assertThat(order.getCommitRetryCount()).isZero();
        assertSingleMarker(eventId);
        assertThat(outboxEvents(MessageTypes.COMMAND_COMMIT_STOCK)).hasSize(1);
        assertThat(outboxEvents()).hasSize(1);
    }

    @Test
    void replayedEventWithNewEventIdIsIgnoredWithoutNewOutboxRow() {
        save(awaitingPaymentOrder());

        deliver(inbound("payment-succeeded.json", UUID.randomUUID()));
        UUID replayId = UUID.randomUUID();
        deliver(inbound("payment-succeeded.json", replayId));

        Order order = reload();
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_COMMIT);
        assertThat(order.getCommitRetryCount()).isZero();
        assertSingleMarker(replayId);
        assertThat(outboxEvents(MessageTypes.COMMAND_COMMIT_STOCK)).hasSize(1);
        assertThat(outboxEvents()).hasSize(1);
    }

    @Test
    void notApplicableEventIsAcknowledgedWithoutChangingOrder() {
        save(newOrder());

        deliver(inbound("payment-succeeded.json", UUID.randomUUID()));

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_RESERVATION);
        assertThat(order.getPaymentId()).isNull();
        assertNoOutbox();
    }

    @Test
    void unknownOrderIsAcknowledgedWithoutWritingAnything() {
        JsonNode envelope = example("payment-succeeded.json");
        retarget(envelope, UUID.randomUUID());
        UUID eventId = UUID.randomUUID();

        deliver(inbound(envelope, eventId));

        assertSingleMarker(eventId);
        Integer orders = jdbcTemplate.queryForObject("SELECT count(*) FROM orders",
                Integer.class);
        assertThat(orders).isZero();
        assertNoOutbox();
    }

    @Test
    void mismatchedReservationIdIsIgnored() {
        save(awaitingPaymentOrder());
        JsonNode envelope = example("inventory-stock-committed.json");
        ((ObjectNode) envelope.get("payload"))
                .put("reservationId", UUID.randomUUID().toString());
        UUID eventId = UUID.randomUUID();

        deliver(inbound(envelope, eventId));

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_PAYMENT);
        assertSingleMarker(eventId);
        assertNoOutbox();
    }

    @Test
    void paymentAmountMismatchIsProcessedAnyway() {
        save(awaitingPaymentOrder());
        JsonNode envelope = example("payment-succeeded.json");
        ((ObjectNode) envelope.get("payload").get("amount")).put("amount", "1.00");

        deliver(inbound(envelope, UUID.randomUUID()));

        Order order = reload();
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_COMMIT);
        assertThat(order.getPaidAmount()).isEqualByComparingTo("1.00");
        assertThat(order.getCommitCommandEventId()).isNotNull();
        assertCommand(singleOutbox(MessageTypes.COMMAND_COMMIT_STOCK));
        assertThat(outboxEvents()).hasSize(1);
    }

    @Test
    void stockCommittedAfterCommitDeadlineConfirmsOrderOnce() {
        save(commitUnconfirmedOrder());
        UUID eventId = UUID.randomUUID();

        deliver(inbound("inventory-stock-committed.json", eventId));

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.isCommitUnconfirmed()).isFalse();
        assertThat(order.getCancelReason()).isNull();
        assertSingleMarker(eventId);
        assertOrderEvent(singleOutbox(MessageTypes.EVENT_ORDER_CONFIRMED));
        assertThat(outboxEvents(MessageTypes.EVENT_ORDER_CANCELLED)).isEmpty();
    }

    @Test
    void stockCommitFailedAfterCommitDeadlineRefundsWithoutCancelledEvent() {
        save(commitUnconfirmedOrder());

        deliver(inbound("inventory-stock-commit-failed.json", UUID.randomUUID()));

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.STOCK_COMMIT_FAILED);
        assertThat(order.isCommitUnconfirmed()).isFalse();
        assertThat(order.getRefundState()).isEqualTo(RefundState.REQUESTED);
        assertThat(order.getRefundCommandEventId()).isNotNull();
        assertThat(order.getRefundDeadlineAt()).isEqualTo(NOW.plus(COMPENSATION_TIMEOUT));

        OutboxEvent refund = singleOutbox(MessageTypes.COMMAND_REFUND_PAYMENT);
        assertCommand(refund);
        assertThat(refund.getEventId()).isEqualTo(order.getRefundCommandEventId());
        assertThat(outboxEvents(MessageTypes.EVENT_ORDER_CANCELLED)).isEmpty();

        deliver(inbound("payment-refunded.json", UUID.randomUUID()));

        Order refunded = reload();
        assertThat(refunded.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(refunded.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(refunded.getRefundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(refunded.getRefundId()).isEqualTo(OrderFixtures.REFUND_ID);
        assertThat(outboxEvents()).hasSize(1);
    }
}
