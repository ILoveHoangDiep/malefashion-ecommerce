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
 * Dòng 16, 17a-17e: hạn chờ inventory.commit-stock, retry cùng eventId và hai nhánh event đến muộn
 * sau khi order đã bỏ cuộc (commit_unconfirmed).
 */
class CommitRetryTest {

    private static final Instant T0 = Instant.parse("2026-10-22T03:15:30.123Z");
    private static final Duration SAGA_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration COMPENSATION_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration COMMIT_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration COMMIT_BACKOFF = Duration.ofSeconds(30);

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
    void paymentSucceededStartsCommitDeadlineAndSendsFirstCommit() {
        SagaStateMachine machine = machine(2);
        moveToAwaitingPayment(machine);

        SagaTransition transition = machine.onPaymentSucceeded(order, OrderFixtures.PAYMENT_ID,
                OrderFixtures.TOTAL, OrderFixtures.PROVIDER_TRANSACTION_ID);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(new SagaAction.SendCommitStock(false));
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_COMMIT);
        assertThat(order.getSagaDeadlineAt()).isNull();
        UUID commandEventId = order.getCommitCommandEventId();
        assertThat(commandEventId).isNotNull();
        assertThat(generatedEventIds).containsExactly(commandEventId);
        assertThat(order.getCommitRetryCount()).isZero();
        assertThat(order.getCommitDeadlineAt()).isEqualTo(T0.plus(COMMIT_TIMEOUT));
        assertThat(order.isCommitUnconfirmed()).isFalse();
    }

    @Test
    void maxRetriesTwoSendsInitialPlusTwoRetriesWithSameEventIdThenFailsOrder() {
        SagaStateMachine machine = machine(2);
        moveToAwaitingCommit(machine);
        UUID commandEventId = order.getCommitCommandEventId();
        assertThat(order.getCommitDeadlineAt()).isEqualTo(T0.plus(COMMIT_TIMEOUT));

        SagaTransition beforeDeadline = machine.onCommitTimeout(order);
        assertThat(beforeDeadline.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);
        assertThat(beforeDeadline.actions()).isEmpty();
        assertThat(order.getCommitRetryCount()).isZero();

        clock.setInstant(T0.plus(COMMIT_TIMEOUT));
        SagaTransition retryOne = machine.onCommitTimeout(order);

        assertThat(retryOne.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(retryOne.actions()).containsExactly(new SagaAction.SendCommitStock(true));
        assertThat(order.getCommitRetryCount()).isEqualTo(1);
        assertThat(order.getCommitCommandEventId()).isEqualTo(commandEventId);
        assertThat(order.getCommitDeadlineAt()).isEqualTo(clock.instant().plus(COMMIT_BACKOFF));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);

        clock.advance(COMMIT_BACKOFF);
        SagaTransition retryTwo = machine.onCommitTimeout(order);

        assertThat(retryTwo.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(retryTwo.actions()).containsExactly(new SagaAction.SendCommitStock(true));
        assertThat(order.getCommitRetryCount()).isEqualTo(2);
        assertThat(order.getCommitCommandEventId()).isEqualTo(commandEventId);
        assertThat(order.getCommitDeadlineAt())
                .isEqualTo(clock.instant().plus(COMMIT_BACKOFF.multipliedBy(2)));

        clock.advance(COMMIT_BACKOFF.multipliedBy(2));
        SagaTransition exhausted = machine.onCommitTimeout(order);

        assertThat(exhausted.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(exhausted.actions()).containsExactly(
                new SagaAction.LogError("COMMIT_RETRY_EXHAUSTED"));
        assertThat(exhausted.actions())
                .noneMatch(action -> action instanceof SagaAction.EmitOrderCancelled);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.isCommitUnconfirmed()).isTrue();
        assertThat(order.getCancelReason()).isNull();
        assertThat(order.getCommitDeadlineAt()).isNull();
        assertThat(generatedEventIds).containsExactly(commandEventId);
    }

    @Test
    void maxRetriesZeroFailsOnFirstDeadlineWithoutSendingAgain() {
        SagaStateMachine machine = machine(0);
        moveToAwaitingCommit(machine);
        UUID commandEventId = order.getCommitCommandEventId();

        clock.setInstant(T0.plus(COMMIT_TIMEOUT));
        SagaTransition transition = machine.onCommitTimeout(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(
                new SagaAction.LogError("COMMIT_RETRY_EXHAUSTED"));
        assertThat(order.getCommitRetryCount()).isZero();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.isCommitUnconfirmed()).isTrue();
        assertThat(order.getCommitDeadlineAt()).isNull();
        assertThat(generatedEventIds).containsExactly(commandEventId);
    }

    @Test
    void stockCommittedAfterUnconfirmedRecoversToConfirmedOnce() {
        SagaStateMachine machine = machine(0);
        moveToAwaitingCommit(machine);
        exhaustCommitRetries(machine);
        assertThat(order.isCommitUnconfirmed()).isTrue();

        SagaTransition transition = machine.onStockCommitted(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(
                new SagaAction.EmitOrderConfirmed(),
                new SagaAction.LogWarn("COMMIT_CONFIRMED_AFTER_DEADLINE"));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.isCommitUnconfirmed()).isFalse();
        assertThat(order.getCancelReason()).isNull();
        assertThat(order.getCommitDeadlineAt()).isNull();

        SagaTransition duplicate = machine.onStockCommitted(order);
        assertThat(duplicate.outcome()).isEqualTo(SagaOutcome.IGNORED_DUPLICATE);
        assertThat(duplicate.actions()).isEmpty();
    }

    @Test
    void stockCommitFailedAfterUnconfirmedRequestsRefundWithoutCancelledEvent() {
        SagaStateMachine machine = machine(0);
        moveToAwaitingCommit(machine);
        exhaustCommitRetries(machine);
        UUID commitEventId = order.getCommitCommandEventId();
        Instant failedAt = clock.instant();

        SagaTransition transition = machine.onStockCommitFailed(order);

        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(transition.actions()).containsExactly(
                new SagaAction.SendRefund(false),
                new SagaAction.LogWarn("COMMIT_FAILED_AFTER_DEADLINE"));
        assertThat(transition.actions())
                .noneMatch(action -> action instanceof SagaAction.EmitOrderCancelled);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.isCommitUnconfirmed()).isFalse();
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.STOCK_COMMIT_FAILED);
        assertThat(order.getRefundState()).isEqualTo(RefundState.REQUESTED);
        assertThat(order.getRefundRetryCount()).isZero();
        assertThat(order.getRefundCommandEventId()).isNotEqualTo(commitEventId);
        assertThat(order.getRefundDeadlineAt()).isEqualTo(failedAt.plus(COMPENSATION_TIMEOUT));

        SagaTransition refunded = machine.onPaymentRefunded(order, OrderFixtures.REFUND_ID);

        assertThat(refunded.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(refunded.actions()).isEmpty();
        assertThat(order.getRefundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(order.getRefundId()).isEqualTo(OrderFixtures.REFUND_ID);
        assertThat(order.getRefundDeadlineAt()).isNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);

        SagaTransition duplicateFailure = machine.onStockCommitFailed(order);
        assertThat(duplicateFailure.outcome()).isEqualTo(SagaOutcome.IGNORED_DUPLICATE);
        assertThat(duplicateFailure.actions()).isEmpty();
    }

    @Test
    void normalStockCommittedAndCommitFailedClearCommitDeadline() {
        SagaStateMachine machine = machine(2);
        moveToAwaitingCommit(machine);
        assertThat(order.getCommitDeadlineAt()).isNotNull();

        machine.onStockCommitted(order);
        assertThat(order.getCommitDeadlineAt()).isNull();

        Order second = OrderFixtures.newOrder(UUID.randomUUID(), "MF-20261022-C0MMT1", T0,
                T0.plus(SAGA_TIMEOUT));
        machine.onStockReserved(second, OrderFixtures.RESERVATION_ID,
                T0.plus(Duration.ofMinutes(15)));
        machine.onPaymentSucceeded(second, OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID);
        assertThat(second.getCommitDeadlineAt()).isNotNull();

        SagaTransition failed = machine.onStockCommitFailed(second);
        assertThat(failed.outcome()).isEqualTo(SagaOutcome.APPLIED);
        assertThat(failed.actions()).containsExactly(new SagaAction.SendRefund(false));
        assertThat(second.getCommitDeadlineAt()).isNull();
    }

    @Test
    void commitTimeoutForOtherStatesOrBeforeDeadlineIsIgnoredWithoutException() {
        SagaStateMachine machine = machine(2);

        // Chưa có commit data (AWAITING_RESERVATION).
        SagaTransition reservation = machine.onCommitTimeout(order);
        assertThat(reservation.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);
        assertThat(reservation.actions()).isEmpty();

        moveToAwaitingCommit(machine);
        SagaTransition beforeDeadline = machine.onCommitTimeout(order);
        assertThat(beforeDeadline.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);

        machine.onStockCommitted(order);
        clock.advance(Duration.ofHours(1));
        SagaTransition afterDone = machine.onCommitTimeout(order);
        assertThat(afterDone.outcome()).isEqualTo(SagaOutcome.IGNORED_NOT_APPLICABLE);
        assertThat(afterDone.actions()).isEmpty();
    }

    private SagaStateMachine machine(int commitMaxRetries) {
        AtomicInteger sequence = new AtomicInteger(1);
        return new SagaStateMachine(
                new SagaTiming(COMPENSATION_TIMEOUT, 2, Duration.ofMinutes(1), 2.0,
                        COMMIT_TIMEOUT, commitMaxRetries, COMMIT_BACKOFF, 2.0),
                clock,
                () -> {
                    UUID eventId = UUID.nameUUIDFromBytes(
                            ("commit-event-" + sequence.getAndIncrement())
                                    .getBytes(StandardCharsets.UTF_8));
                    generatedEventIds.add(eventId);
                    return eventId;
                });
    }

    private void moveToAwaitingPayment(SagaStateMachine machine) {
        SagaTransition transition = machine.onStockReserved(order, OrderFixtures.RESERVATION_ID,
                T0.plus(Duration.ofMinutes(15)));
        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
    }

    private void moveToAwaitingCommit(SagaStateMachine machine) {
        moveToAwaitingPayment(machine);
        SagaTransition transition = machine.onPaymentSucceeded(order, OrderFixtures.PAYMENT_ID,
                OrderFixtures.TOTAL, OrderFixtures.PROVIDER_TRANSACTION_ID);
        assertThat(transition.outcome()).isEqualTo(SagaOutcome.APPLIED);
    }

    private void exhaustCommitRetries(SagaStateMachine machine) {
        clock.setInstant(T0.plus(COMMIT_TIMEOUT));
        SagaTransition transition = machine.onCommitTimeout(order);
        assertThat(transition.actions()).containsExactly(
                new SagaAction.LogError("COMMIT_RETRY_EXHAUSTED"));
        assertThat(order.isCommitUnconfirmed()).isTrue();
        assertThat(order.getCommitDeadlineAt()).isNull();
    }
}
