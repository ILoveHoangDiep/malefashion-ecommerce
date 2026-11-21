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

/**
 * Retry refund với payment-service KHÔNG phản hồi: không có payment.refunded nào được đưa vào giữa
 * các lần quá hạn.
 */
class RefundRetryTest {

    private static final Instant T0 = Instant.parse("2026-10-22T03:15:30.123Z");
    private static final Duration SAGA_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration COMPENSATION_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration BACKOFF = Duration.ofMinutes(1);

    private MutableClock clock;
    private List<UUID> generatedEventIds;
    private Order order;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0, ZoneOffset.UTC);
        generatedEventIds = new ArrayList<>();
        order = OrderFixtures.newOrder(T0, T0.plus(SAGA_TIMEOUT));
    }

    @Test
    void maxRetriesTwoSendsInitialPlusTwoRetriesWithSameEventIdThenFailsOrder() {
        SagaStateMachine machine = machine(2);
        SagaTransition initial = moveToAwaitingRefund(machine);
        assertThat(initial.actions()).containsExactly(new SagaAction.SendRefund(false));

        UUID commitEventId = order.getCommitCommandEventId();
        UUID commandEventId = order.getRefundCommandEventId();
        assertThat(commandEventId).isNotNull();
        assertThat(order.getRefundDeadlineAt()).isEqualTo(T0.plus(COMPENSATION_TIMEOUT));

        clock.setInstant(T0.plus(COMPENSATION_TIMEOUT));
        SagaTransition retryOne = machine.onRefundTimeout(order);

        assertThat(retryOne.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(retryOne.actions()).containsExactly(new SagaAction.SendRefund(true));
        assertThat(order.getRefundRetryCount()).isEqualTo(1);
        assertThat(order.getRefundCommandEventId()).isEqualTo(commandEventId);
        assertThat(order.getRefundDeadlineAt()).isEqualTo(clock.instant().plus(BACKOFF));

        clock.advance(BACKOFF);
        SagaTransition retryTwo = machine.onRefundTimeout(order);

        assertThat(retryTwo.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(retryTwo.actions()).containsExactly(new SagaAction.SendRefund(true));
        assertThat(order.getRefundRetryCount()).isEqualTo(2);
        assertThat(order.getRefundCommandEventId()).isEqualTo(commandEventId);
        assertThat(order.getRefundDeadlineAt()).isEqualTo(clock.instant().plus(BACKOFF.multipliedBy(2)));

        clock.advance(BACKOFF.multipliedBy(2));
        SagaTransition exhausted = machine.onRefundTimeout(order);

        assertThat(exhausted.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(exhausted.actions()).containsExactly(
                new SagaAction.LogError("REFUND_RETRY_EXHAUSTED"));
        assertThat(exhausted.actions())
                .noneMatch(action -> action instanceof SagaAction.EmitOrderCancelled);
        assertThat(order.getRefundState()).isEqualTo(RefundState.REFUND_FAILED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.STOCK_COMMIT_FAILED);
        assertThat(order.getRefundDeadlineAt()).isNull();
        assertThat(generatedEventIds).containsExactly(commitEventId, commandEventId);
    }

    @Test
    void maxRetriesZeroFailsOnFirstDeadlineWithoutSendingAgain() {
        SagaStateMachine machine = machine(0);
        moveToAwaitingRefund(machine);
        UUID commitEventId = order.getCommitCommandEventId();
        UUID commandEventId = order.getRefundCommandEventId();

        clock.setInstant(T0.plus(COMPENSATION_TIMEOUT));
        SagaTransition transition = machine.onRefundTimeout(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(
                new SagaAction.LogError("REFUND_RETRY_EXHAUSTED"));
        assertThat(order.getRefundRetryCount()).isZero();
        assertThat(order.getRefundState()).isEqualTo(RefundState.REFUND_FAILED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(generatedEventIds).containsExactly(commitEventId, commandEventId);
    }

    @Test
    void paymentRefundedDuringRetryStopsFurtherRetriesAndCancelsOnce() {
        SagaStateMachine machine = machine(2);
        moveToAwaitingRefund(machine);
        UUID commandEventId = order.getRefundCommandEventId();

        clock.setInstant(T0.plus(COMPENSATION_TIMEOUT));
        machine.onRefundTimeout(order);
        assertThat(order.getRefundRetryCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(10));
        SagaTransition refunded = machine.onPaymentRefunded(order, OrderFixtures.REFUND_ID);

        assertThat(refunded.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(refunded.actions()).containsExactly(new SagaAction.EmitOrderCancelled());
        assertThat(order.getRefundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(order.getRefundRetryCount()).isEqualTo(1);
        assertThat(order.getRefundCommandEventId()).isEqualTo(commandEventId);
        assertThat(order.getRefundDeadlineAt()).isNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.STOCK_COMMIT_FAILED);

        SagaTransition afterRefund = machine.onRefundTimeout(order);
        assertThat(afterRefund.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);
        assertThat(afterRefund.actions()).isEmpty();

        SagaTransition duplicateRefund = machine.onPaymentRefunded(order,
                OrderFixtures.REFUND_ID);
        assertThat(duplicateRefund.outcome()).isEqualTo(SagaOutcome.IGNORED_DUPLICATE);
    }

    @Test
    void latePaymentBranchKeepsCancelledStatusWhenRetriesExhausted() {
        SagaStateMachine machine = machine(2);
        clock.setInstant(T0.plus(SAGA_TIMEOUT));
        machine.onSagaTimeout(order);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);

        SagaTransition latePayment = machine.onPaymentSucceeded(order,
                OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID);
        assertThat(latePayment.actions()).containsExactly(new SagaAction.SendRefund(false));
        UUID commandEventId = order.getRefundCommandEventId();

        clock.advance(COMPENSATION_TIMEOUT);
        machine.onRefundTimeout(order);
        clock.advance(BACKOFF);
        machine.onRefundTimeout(order);
        clock.advance(BACKOFF.multipliedBy(2));
        SagaTransition exhausted = machine.onRefundTimeout(order);

        assertThat(exhausted.actions()).containsExactly(
                new SagaAction.LogError("REFUND_RETRY_EXHAUSTED"));
        assertThat(order.getRefundState()).isEqualTo(RefundState.REFUND_FAILED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getRefundCommandEventId()).isEqualTo(commandEventId);
    }

    @Test
    void latePaymentRefundedDuringRetryKeepsStatusAndEmitsNothing() {
        SagaStateMachine machine = machine(2);
        clock.setInstant(T0.plus(SAGA_TIMEOUT));
        machine.onSagaTimeout(order);
        machine.onPaymentSucceeded(order, OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID);
        UUID commandEventId = order.getRefundCommandEventId();

        clock.advance(COMPENSATION_TIMEOUT);
        machine.onRefundTimeout(order);
        assertThat(order.getRefundRetryCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(5));
        SagaTransition refunded = machine.onPaymentRefunded(order, OrderFixtures.REFUND_ID);

        assertThat(refunded.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(refunded.actions()).isEmpty();
        assertThat(order.getRefundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(order.getRefundId()).isEqualTo(OrderFixtures.REFUND_ID);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getRefundCommandEventId()).isEqualTo(commandEventId);
        assertThat(order.getRefundDeadlineAt()).isNull();
    }

    private SagaStateMachine machine(int maxRetries) {
        AtomicInteger sequence = new AtomicInteger(1);
        return new SagaStateMachine(
                new SagaTiming(COMPENSATION_TIMEOUT, maxRetries, BACKOFF, 2.0,
                        Duration.ofMinutes(1), 2, Duration.ofSeconds(30), 2.0),
                clock,
                () -> {
                    UUID eventId = UUID.nameUUIDFromBytes(
                            ("refund-event-" + sequence.getAndIncrement())
                                    .getBytes(StandardCharsets.UTF_8));
                    generatedEventIds.add(eventId);
                    return eventId;
                });
    }

    private SagaTransition moveToAwaitingRefund(SagaStateMachine machine) {
        machine.onStockReserved(order, OrderFixtures.RESERVATION_ID,
                T0.plus(Duration.ofMinutes(15)));
        machine.onPaymentSucceeded(order, OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID);
        return machine.onStockCommitFailed(order);
    }
}
