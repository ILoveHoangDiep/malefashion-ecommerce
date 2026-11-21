package com.malefashion.order.integration;

import com.malefashion.order.application.OrderDeadlineService;
import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.OrderStatus;
import com.malefashion.order.domain.model.RefundState;
import com.malefashion.order.domain.model.SagaStep;
import com.malefashion.order.infrastructure.messaging.MessageTypes;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.SagaHandlerTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C.13: mỗi method của {@link OrderDeadlineService} là một transaction trên một order, kiểm tra lại
 * hạn nên gọi sớm/gọi trùng đều không ghi gì.
 */
class OrderDeadlineServiceIT extends SagaHandlerTestBase {

    @Autowired
    private OrderDeadlineService deadlineService;

    @Test
    void sagaDeadlineCancelsReservationTimeoutExactlyOnce() {
        save(newOrder());

        assertThat(deadlineService.onSagaDeadline(OrderFixtures.ORDER_ID)).isFalse();
        assertNoOutbox();

        clock.setInstant(NOW.plus(SAGA_TIMEOUT));
        assertThat(deadlineService.onSagaDeadline(OrderFixtures.ORDER_ID)).isTrue();

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(order.getSagaDeadlineAt()).isNull();
        assertOrderEvent(singleOutbox(MessageTypes.EVENT_ORDER_CANCELLED));

        assertThat(deadlineService.onSagaDeadline(OrderFixtures.ORDER_ID)).isFalse();
        assertThat(outboxEvents()).hasSize(1);
    }

    @Test
    void sagaDeadlineReleasesStockThenCompensationDeadlineCancels() {
        save(awaitingPaymentOrder());

        clock.setInstant(NOW.plus(SAGA_TIMEOUT));
        assertThat(deadlineService.onSagaDeadline(OrderFixtures.ORDER_ID)).isTrue();

        Order releasing = reload();
        assertThat(releasing.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(releasing.getSagaStep()).isEqualTo(SagaStep.AWAITING_RELEASE);
        assertThat(releasing.getPendingCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);
        assertThat(releasing.getSagaDeadlineAt()).isNull();
        assertThat(releasing.getCompensationDeadlineAt())
                .isEqualTo(NOW.plus(SAGA_TIMEOUT).plus(COMPENSATION_TIMEOUT));

        OutboxEvent release = singleOutbox(MessageTypes.COMMAND_RELEASE_STOCK);
        assertCommand(release);
        assertThat(payloadOf(release).path("reason").asText()).isEqualTo("SAGA_TIMEOUT");

        assertThat(deadlineService.onCompensationDeadline(OrderFixtures.ORDER_ID)).isFalse();

        clock.advance(COMPENSATION_TIMEOUT);
        assertThat(deadlineService.onCompensationDeadline(OrderFixtures.ORDER_ID)).isTrue();

        Order cancelled = reload();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCancelReason()).isEqualTo(CancelReason.SAGA_TIMEOUT);
        assertThat(cancelled.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(cancelled.getCompensationDeadlineAt()).isNull();
        assertOrderEvent(singleOutbox(MessageTypes.EVENT_ORDER_CANCELLED));

        assertThat(deadlineService.onCompensationDeadline(OrderFixtures.ORDER_ID)).isFalse();
        assertThat(outboxEvents()).hasSize(2);
    }

    @Test
    void refundDeadlineRetriesWithSameEventIdThenGivesUp() {
        Order saved = awaitingRefundOrder();
        UUID refundEventId = saved.getRefundCommandEventId();

        assertThat(deadlineService.onRefundDeadline(OrderFixtures.ORDER_ID)).isFalse();

        clock.advance(COMPENSATION_TIMEOUT);
        assertThat(deadlineService.onRefundDeadline(OrderFixtures.ORDER_ID)).isTrue();

        Order retryOne = reload();
        assertThat(retryOne.getRefundState()).isEqualTo(RefundState.REQUESTED);
        assertThat(retryOne.getRefundRetryCount()).isEqualTo(1);
        assertThat(retryOne.getRefundDeadlineAt())
                .isEqualTo(clock.instant().plus(REFUND_BACKOFF));

        clock.advance(REFUND_BACKOFF);
        assertThat(deadlineService.onRefundDeadline(OrderFixtures.ORDER_ID)).isTrue();

        Order retryTwo = reload();
        assertThat(retryTwo.getRefundRetryCount()).isEqualTo(2);
        assertThat(retryTwo.getRefundDeadlineAt())
                .isEqualTo(clock.instant().plus(REFUND_BACKOFF.multipliedBy(2)));

        List<OutboxEvent> refunds = outboxEvents(MessageTypes.COMMAND_REFUND_PAYMENT);
        assertThat(refunds).hasSize(2);
        assertThat(refunds).allSatisfy(refund -> {
            assertThat(refund.getEventId()).isEqualTo(refundEventId);
            assertCommand(refund);
        });
        assertThat(refunds.get(0).getId()).isNotEqualTo(refunds.get(1).getId());

        clock.advance(REFUND_BACKOFF.multipliedBy(2));
        assertThat(deadlineService.onRefundDeadline(OrderFixtures.ORDER_ID)).isTrue();

        Order failed = reload();
        assertThat(failed.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(failed.getCancelReason()).isEqualTo(CancelReason.STOCK_COMMIT_FAILED);
        assertThat(failed.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(failed.getRefundState()).isEqualTo(RefundState.REFUND_FAILED);
        assertThat(failed.getRefundDeadlineAt()).isNull();
        assertThat(outboxEvents(MessageTypes.COMMAND_REFUND_PAYMENT)).hasSize(2);
        assertThat(outboxEvents(MessageTypes.EVENT_ORDER_CANCELLED)).isEmpty();

        assertThat(deadlineService.onRefundDeadline(OrderFixtures.ORDER_ID)).isFalse();
    }

    @Test
    void commitDeadlineRetriesWithSameEventIdThenMarksOrderUnconfirmed() {
        Order saved = awaitingCommitOrder();
        UUID commitEventId = saved.getCommitCommandEventId();

        assertThat(deadlineService.onCommitDeadline(OrderFixtures.ORDER_ID)).isFalse();
        assertNoOutbox();

        clock.advance(COMMIT_TIMEOUT);
        assertThat(deadlineService.onCommitDeadline(OrderFixtures.ORDER_ID)).isTrue();

        Order retryOne = reload();
        assertThat(retryOne.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(retryOne.getSagaStep()).isEqualTo(SagaStep.AWAITING_COMMIT);
        assertThat(retryOne.getCommitRetryCount()).isEqualTo(1);
        assertThat(retryOne.getCommitDeadlineAt())
                .isEqualTo(clock.instant().plus(COMMIT_BACKOFF));

        clock.advance(COMMIT_BACKOFF);
        assertThat(deadlineService.onCommitDeadline(OrderFixtures.ORDER_ID)).isTrue();

        Order retryTwo = reload();
        assertThat(retryTwo.getCommitRetryCount()).isEqualTo(2);
        assertThat(retryTwo.getCommitDeadlineAt())
                .isEqualTo(clock.instant().plus(COMMIT_BACKOFF.multipliedBy(2)));

        List<OutboxEvent> commits = outboxEvents(MessageTypes.COMMAND_COMMIT_STOCK);
        assertThat(commits).hasSize(2);
        assertThat(commits).allSatisfy(commit -> {
            assertThat(commit.getEventId()).isEqualTo(commitEventId);
            assertCommand(commit);
        });
        assertThat(commits.get(0).getId()).isNotEqualTo(commits.get(1).getId());

        clock.advance(COMMIT_BACKOFF.multipliedBy(2));
        assertThat(deadlineService.onCommitDeadline(OrderFixtures.ORDER_ID)).isTrue();

        Order unconfirmed = reload();
        assertThat(unconfirmed.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(unconfirmed.getSagaStep()).isEqualTo(SagaStep.DONE);
        assertThat(unconfirmed.isCommitUnconfirmed()).isTrue();
        assertThat(unconfirmed.getCancelReason()).isNull();
        assertThat(unconfirmed.getCommitDeadlineAt()).isNull();
        assertThat(outboxEvents(MessageTypes.COMMAND_COMMIT_STOCK)).hasSize(2);
        assertThat(outboxEvents(MessageTypes.EVENT_ORDER_CANCELLED)).isEmpty();

        assertThat(deadlineService.onCommitDeadline(OrderFixtures.ORDER_ID)).isFalse();
    }

    @Test
    void deadlineForUnknownOrderIsSkippedWithoutWriting() {
        assertThat(deadlineService.onSagaDeadline(UUID.randomUUID())).isFalse();
        assertThat(deadlineService.onCompensationDeadline(UUID.randomUUID())).isFalse();
        assertThat(deadlineService.onRefundDeadline(UUID.randomUUID())).isFalse();
        assertThat(deadlineService.onCommitDeadline(UUID.randomUUID())).isFalse();
        assertNoOutbox();
    }
}
