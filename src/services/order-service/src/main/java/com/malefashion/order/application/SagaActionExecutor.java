package com.malefashion.order.application;

import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.saga.SagaAction;
import com.malefashion.order.infrastructure.messaging.OrderMessageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Dịch {@link SagaAction} thành ghi outbox hoặc log trong transaction hiện tại. Command và event
 * order.* được enqueue qua {@link OutboxService} (MANDATORY) để cùng commit/rollback với thay đổi
 * order và marker inbox.
 *
 * <p>Retry (commit/refund) dùng đúng eventId đã lưu trên order; lần gửi đầu và mọi lần gửi lại đều
 * tạo DÒNG OUTBOX MỚI nhưng cùng event_id.</p>
 */
@Component
public class SagaActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(SagaActionExecutor.class);

    private final OrderMessageFactory messageFactory;
    private final OutboxService outboxService;

    public SagaActionExecutor(OrderMessageFactory messageFactory, OutboxService outboxService) {
        this.messageFactory = messageFactory;
        this.outboxService = outboxService;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(List<SagaAction> actions, Order order) {
        Objects.requireNonNull(order, "order must not be null");
        for (SagaAction action : actions) {
            execute(action, order);
        }
    }

    private void execute(SagaAction action, Order order) {
        switch (action) {
            case SagaAction.SendProcessPayment ignored -> outboxService.appendCommand(
                    messageFactory.processPayment(UUID.randomUUID(), order));
            case SagaAction.SendCommitStock ignored -> outboxService.appendCommand(
                    messageFactory.commitStock(commitEventId(order), order.getId(),
                            reservationId(order)));
            case SagaAction.SendReleaseStock release -> outboxService.appendCommand(
                    messageFactory.releaseStock(UUID.randomUUID(), order.getId(),
                            reservationId(order), release.reason()));
            case SagaAction.SendRefund ignored -> outboxService.appendCommand(
                    messageFactory.refundPayment(refundEventId(order), order));
            case SagaAction.EmitOrderConfirmed ignored -> outboxService.appendEvent(
                    messageFactory.orderConfirmed(order));
            case SagaAction.EmitOrderCancelled ignored -> outboxService.appendEvent(
                    messageFactory.orderCancelled(order, cancelReason(order)));
            case SagaAction.LogWarn warn -> log.warn("Saga warning orderId={} code={}",
                    order.getId(), warn.code());
            case SagaAction.LogError error -> log.error("Saga error orderId={} code={}",
                    order.getId(), error.code());
        }
    }

    private UUID commitEventId(Order order) {
        return Objects.requireNonNull(order.getCommitCommandEventId(),
                "order.commitCommandEventId must be set before commit");
    }

    private UUID refundEventId(Order order) {
        return Objects.requireNonNull(order.getRefundCommandEventId(),
                "order.refundCommandEventId must be set before refund");
    }

    private UUID reservationId(Order order) {
        return Objects.requireNonNull(order.getReservationId(),
                "order.reservationId must be set before release/commit stock");
    }

    private CancelReason cancelReason(Order order) {
        return Objects.requireNonNull(order.getCancelReason(),
                "order.cancelReason must be set before order.cancelled");
    }
}
