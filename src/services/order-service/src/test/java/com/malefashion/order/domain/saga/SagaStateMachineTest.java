package com.malefashion.order.domain.saga;

import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.OrderStatus;
import com.malefashion.order.domain.model.RefundState;
import com.malefashion.order.domain.model.SagaStep;
import com.malefashion.order.support.MutableClock;
import com.malefashion.order.support.OrderFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SagaStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-10-22T03:15:30.123Z");
    private static final Duration SAGA_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration COMPENSATION_TIMEOUT = Duration.ofMinutes(2);

    private MutableClock clock;
    private SagaStateMachine machine;
    private Order order;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0, ZoneOffset.UTC);
        machine = newMachine(2);
        order = OrderFixtures.newOrder(T0, T0.plus(SAGA_TIMEOUT));
    }

    @Test
    void row1_stockReservedMovesToAwaitingPaymentAndSendsProcessPayment() {
        SagaTransition transition = machine.onStockReserved(order, OrderFixtures.RESERVATION_ID,
                T0.plus(Duration.ofMinutes(15)));

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(new SagaAction.SendProcessPayment());
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_PAYMENT);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getReservationId()).isEqualTo(OrderFixtures.RESERVATION_ID);
        assertThat(order.getReservationExpiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
    }

    @Test
    void row2_reservationFailedCancelsWithoutRelease() {
        SagaTransition transition = machine.onStockReservationFailed(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(new SagaAction.EmitOrderCancelled());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.OUT_OF_STOCK);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getSagaDeadlineAt()).isNull();
    }

    @Test
    void row3_paymentSucceededMovesToAwaitingCommitAndSendsCommitStock() {
        moveToAwaitingPayment();

        SagaTransition transition = machine.onPaymentSucceeded(order, OrderFixtures.PAYMENT_ID,
                OrderFixtures.TOTAL, OrderFixtures.PROVIDER_TRANSACTION_ID);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(new SagaAction.SendCommitStock());
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_COMMIT);
        assertThat(order.getPaymentId()).isEqualTo(OrderFixtures.PAYMENT_ID);
        assertThat(order.getPaidAmount()).isEqualByComparingTo("578000.00");
        assertThat(order.getProviderTransactionId())
                .isEqualTo(OrderFixtures.PROVIDER_TRANSACTION_ID);
        assertThat(order.getSagaDeadlineAt()).isNull();
    }

    @Test
    void row4_paymentFailedStartsReleaseWithCompensationDeadline() {
        moveToAwaitingPayment();

        SagaTransition transition = machine.onPaymentFailed(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(
                new SagaAction.SendReleaseStock(StockReleaseReason.PAYMENT_FAILED));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_RELEASE);
        assertThat(order.getPendingCancelReason()).isEqualTo(CancelReason.PAYMENT_FAILED);
        assertThat(order.getCompensationDeadlineAt()).isEqualTo(T0.plus(COMPENSATION_TIMEOUT));
    }

    @Test
    void row5_awaitingPaymentTimeoutStartsReleaseWithTimeoutReason() {
        moveToAwaitingPayment();
        clock.setInstant(T0.plus(SAGA_TIMEOUT));

        SagaTransition transition = machine.onSagaTimeout(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(
                new SagaAction.SendReleaseStock(StockReleaseReason.SAGA_TIMEOUT));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_RELEASE);
        assertThat(order.getPendingCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);
        assertThat(order.getSagaDeadlineAt()).isNull();
        assertThat(order.getCompensationDeadlineAt())
                .isEqualTo(T0.plus(SAGA_TIMEOUT).plus(COMPENSATION_TIMEOUT));
    }

    @Test
    void row5_sagaTimeoutBeforeDeadlineIsNotApplied() {
        moveToAwaitingPayment();

        SagaTransition transition = machine.onSagaTimeout(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);
        assertThat(transition.actions()).isEmpty();
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_PAYMENT);
    }

    @Test
    void row6_awaitingReservationTimeoutCancelsWithoutRelease() {
        clock.setInstant(T0.plus(SAGA_TIMEOUT));

        SagaTransition transition = machine.onSagaTimeout(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(new SagaAction.EmitOrderCancelled());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
    }

    @Test
    void row7_stockCommittedConfirmsOrderAndEmitsConfirmed() {
        moveToAwaitingCommit();

        SagaTransition transition = machine.onStockCommitted(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(new SagaAction.EmitOrderConfirmed());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getCompensationDeadlineAt()).isNull();
    }

    @Test
    void row8_stockCommitFailedStartsRefundWithOneEventId() {
        moveToAwaitingCommit();

        SagaTransition transition = machine.onStockCommitFailed(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(new SagaAction.SendRefund(false));
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_REFUND);
        assertThat(order.getPendingCancelReason()).isEqualTo(CancelReason.STOCK_COMMIT_FAILED);
        assertThat(order.getRefundState()).isEqualTo(RefundState.REQUESTED);
        assertThat(order.getRefundCommandEventId()).isNotNull();
        assertThat(order.getRefundRetryCount()).isZero();
        assertThat(order.getRefundDeadlineAt()).isEqualTo(T0.plus(COMPENSATION_TIMEOUT));
    }

    @Test
    void row9_stockReleasedCancelsWithPendingReasonAndEmitsOnce() {
        moveToAwaitingPayment();
        machine.onPaymentFailed(order);

        SagaTransition transition = machine.onStockReleased(order,
                StockReleaseReason.PAYMENT_FAILED);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(new SagaAction.EmitOrderCancelled());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.PAYMENT_FAILED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getCompensationDeadlineAt()).isNull();
    }

    @Test
    void row10_paymentRefundedCancelsCommitFailedOrderAndEmitsOnce() {
        moveToAwaitingRefund();

        SagaTransition transition = machine.onPaymentRefunded(order, OrderFixtures.REFUND_ID);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(new SagaAction.EmitOrderCancelled());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.STOCK_COMMIT_FAILED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getRefundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(order.getRefundId()).isEqualTo(OrderFixtures.REFUND_ID);
        assertThat(order.getRefundDeadlineAt()).isNull();
    }

    @Test
    void row11_ttlReleaseDuringPaymentOrCommitIsOnlyAcknowledged() {
        moveToAwaitingPayment();
        SagaStep step = order.getSagaStep();

        SagaTransition duringPayment = machine.onStockReleased(order,
                StockReleaseReason.RESERVATION_EXPIRED);

        assertThat(duringPayment.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(duringPayment.actions()).isEmpty();
        assertThat(order.getSagaStep()).isEqualTo(step);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getCancelReason()).isNull();

        SagaTransition paymentTransition = machine.onPaymentSucceeded(order,
                OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID);
        assertThat(paymentTransition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        SagaStep commitStep = order.getSagaStep();

        SagaTransition duringCommit = machine.onStockReleased(order,
                StockReleaseReason.RESERVATION_EXPIRED);

        assertThat(duringCommit.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(duringCommit.actions()).isEmpty();
        assertThat(order.getSagaStep()).isEqualTo(commitStep);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void row12_latePaymentAfterTimeoutCancellationRequestsRefundWithoutSecondCancelledEvent() {
        clock.setInstant(T0.plus(SAGA_TIMEOUT));
        machine.onSagaTimeout(order);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        SagaTransition paymentTransition = machine.onPaymentSucceeded(order,
                OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID);

        assertThat(paymentTransition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(paymentTransition.actions()).containsExactly(new SagaAction.SendRefund(false));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getRefundState()).isEqualTo(RefundState.REQUESTED);
        assertThat(order.getPaymentId()).isEqualTo(OrderFixtures.PAYMENT_ID);

        SagaTransition refundTransition = machine.onPaymentRefunded(order,
                OrderFixtures.REFUND_ID);

        assertThat(refundTransition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(refundTransition.actions()).isEmpty();
        assertThat(order.getRefundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);
    }

    @Test
    void row12_latePaymentWhileAwaitingReleaseRequestsRefundAndReleaseStillEmitsCancelled() {
        moveToAwaitingPayment();
        clock.setInstant(T0.plus(SAGA_TIMEOUT));
        machine.onSagaTimeout(order);

        SagaTransition paymentTransition = machine.onPaymentSucceeded(order,
                OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID);

        assertThat(paymentTransition.actions()).containsExactly(new SagaAction.SendRefund(false));
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_RELEASE);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);

        SagaTransition refundTransition = machine.onPaymentRefunded(order,
                OrderFixtures.REFUND_ID);
        assertThat(refundTransition.actions()).isEmpty();
        assertThat(order.getRefundState()).isEqualTo(RefundState.REFUNDED);

        SagaTransition releaseTransition = machine.onStockReleased(order,
                StockReleaseReason.SAGA_TIMEOUT);
        assertThat(releaseTransition.actions()).containsExactly(new SagaAction.EmitOrderCancelled());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);
    }

    @Test
    void row13_lateReservationAfterTimeoutCancellationIsReleasedWithoutSecondCancelledEvent() {
        clock.setInstant(T0.plus(SAGA_TIMEOUT));
        machine.onSagaTimeout(order);

        SagaTransition transition = machine.onStockReserved(order, OrderFixtures.RESERVATION_ID,
                T0.plus(Duration.ofMinutes(30)));

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(
                new SagaAction.SendReleaseStock(StockReleaseReason.SAGA_TIMEOUT));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getReservationId()).isEqualTo(OrderFixtures.RESERVATION_ID);
    }

    @Test
    void row14a_compensationTimeoutCancelsAndWarns() {
        moveToAwaitingPayment();
        machine.onPaymentFailed(order);
        clock.setInstant(T0.plus(COMPENSATION_TIMEOUT));

        SagaTransition transition = machine.onCompensationTimeout(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(
                new SagaAction.EmitOrderCancelled(),
                new SagaAction.LogWarn("STOCK_RELEASE_TIMEOUT"));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.PAYMENT_FAILED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getCompensationDeadlineAt()).isNull();
    }

    @Test
    void row14a_compensationTimeoutBeforeDeadlineIsIgnored() {
        moveToAwaitingPayment();
        machine.onPaymentFailed(order);

        SagaTransition transition = machine.onCompensationTimeout(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_RELEASE);
    }

    @Test
    void row14d_refundedAfterRefundFailedIsIgnoredWithWarn() {
        moveToAwaitingRefund();
        exhaustRefundRetries();
        assertThat(order.getRefundState()).isEqualTo(RefundState.REFUND_FAILED);

        SagaTransition transition = machine.onPaymentRefunded(order, OrderFixtures.REFUND_ID);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);
        assertThat(transition.actions()).containsExactly(
                new SagaAction.LogWarn("REFUND_ALREADY_FAILED"));
        assertThat(order.getRefundState()).isEqualTo(RefundState.REFUND_FAILED);
    }

    @Test
    void duplicateEventsAreIgnoredWithoutException() {
        moveToAwaitingPayment();

        SagaTransition duplicateReservation = machine.onStockReserved(order,
                OrderFixtures.RESERVATION_ID, T0.plus(Duration.ofMinutes(15)));
        assertThat(duplicateReservation.outcome()).isEqualTo(SagaOutcome.IGNORED_DUPLICATE);
        assertThat(duplicateReservation.actions()).isEmpty();

        SagaTransition paymentTransition = machine.onPaymentSucceeded(order,
                OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID);
        assertThat(paymentTransition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        SagaTransition duplicatePayment = machine.onPaymentSucceeded(order,
                OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID);
        assertThat(duplicatePayment.outcome()).isEqualTo(SagaOutcome.IGNORED_DUPLICATE);

        machine.onStockCommitted(order);
        SagaTransition duplicateCommit = machine.onStockCommitted(order);
        assertThat(duplicateCommit.outcome()).isEqualTo(SagaOutcome.IGNORED_DUPLICATE);

        // release lặp lại phải được kiểm tra trên một đơn đã thực sự đi qua nhánh release.
        Order releaseOrder = OrderFixtures.newOrder(T0, T0.plus(SAGA_TIMEOUT));
        machine.onStockReserved(releaseOrder, OrderFixtures.RESERVATION_ID,
                T0.plus(Duration.ofMinutes(15)));
        machine.onPaymentFailed(releaseOrder);
        SagaTransition released = machine.onStockReleased(releaseOrder,
                StockReleaseReason.PAYMENT_FAILED);
        assertThat(released.outcome()).isEqualTo(SagaOutcome.APPLIED);

        SagaTransition duplicateRelease = machine.onStockReleased(releaseOrder,
                StockReleaseReason.PAYMENT_FAILED);
        assertThat(duplicateRelease.outcome()).isEqualTo(SagaOutcome.IGNORED_DUPLICATE);
    }

    @Test
    void duplicateCancelEventsAreIgnored() {
        machine.onStockReservationFailed(order);

        SagaTransition duplicate = machine.onStockReservationFailed(order);

        assertThat(duplicate.outcome()).isEqualTo(SagaOutcome.IGNORED_DUPLICATE);
        assertThat(duplicate.actions()).isEmpty();

        SagaTransition timeout = machine.onSagaTimeout(order);
        assertThat(timeout.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);
    }

    @Test
    void duplicateRefundEventsDoNotCreateAnotherRefundCommand() {
        moveToAwaitingRefund();
        UUID firstEventId = order.getRefundCommandEventId();

        SagaTransition duplicateCommitFailure = machine.onStockCommitFailed(order);

        assertThat(duplicateCommitFailure.outcome()).isEqualTo(SagaOutcome.IGNORED_DUPLICATE);
        assertThat(order.getRefundCommandEventId()).isEqualTo(firstEventId);

        machine.onPaymentRefunded(order, OrderFixtures.REFUND_ID);
        SagaTransition duplicateRefund = machine.onPaymentRefunded(order,
                OrderFixtures.REFUND_ID);
        assertThat(duplicateRefund.outcome()).isEqualTo(SagaOutcome.IGNORED_DUPLICATE);
        assertThat(duplicateRefund.actions()).isEmpty();
    }

    @Test
    void eventsForFinishedOrUnrelatedStatesAreIgnoredWithoutException() {
        moveToAwaitingCommit();
        machine.onStockCommitted(order);

        SagaTransition lateReservation = machine.onStockReserved(order,
                OrderFixtures.RESERVATION_ID, T0.plus(Duration.ofMinutes(15)));
        assertThat(lateReservation.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);

        SagaTransition refundWithoutRequest = machine.onPaymentRefunded(order,
                OrderFixtures.REFUND_ID);
        assertThat(refundWithoutRequest.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);

        SagaTransition compensationTimeout = machine.onCompensationTimeout(order);
        assertThat(compensationTimeout.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);

        SagaTransition refundTimeout = machine.onRefundTimeout(order);
        assertThat(refundTimeout.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);
    }

    private SagaStateMachine newMachine(int refundMaxRetries) {
        AtomicInteger sequence = new AtomicInteger(1);
        return new SagaStateMachine(
                new SagaTiming(COMPENSATION_TIMEOUT, refundMaxRetries,
                        Duration.ofMinutes(1), 2.0),
                clock,
                () -> UUID.nameUUIDFromBytes(
                        ("event-" + sequence.getAndIncrement()).getBytes(StandardCharsets.UTF_8)));
    }

    private void moveToAwaitingPayment() {
        SagaTransition transition = machine.onStockReserved(order, OrderFixtures.RESERVATION_ID,
                T0.plus(Duration.ofMinutes(15)));
        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
    }

    private void moveToAwaitingCommit() {
        moveToAwaitingPayment();
        SagaTransition transition = machine.onPaymentSucceeded(order, OrderFixtures.PAYMENT_ID,
                OrderFixtures.TOTAL, OrderFixtures.PROVIDER_TRANSACTION_ID);
        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
    }

    private void moveToAwaitingRefund() {
        moveToAwaitingCommit();
        SagaTransition transition = machine.onStockCommitFailed(order);
        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
    }

    private void exhaustRefundRetries() {
        List<UUID> generated = new ArrayList<>();
        generated.add(order.getRefundCommandEventId());
        for (int attempt = 0; attempt <= 2; attempt++) {
            clock.advance(COMPENSATION_TIMEOUT.plus(Duration.ofMinutes(2)));
            machine.onRefundTimeout(order);
        }
        assertThat(order.getRefundState()).isEqualTo(RefundState.REFUND_FAILED);
        assertThat(order.getRefundCommandEventId()).isEqualTo(generated.getFirst());
    }
}
