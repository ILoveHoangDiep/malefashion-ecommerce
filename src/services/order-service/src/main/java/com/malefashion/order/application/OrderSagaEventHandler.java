package com.malefashion.order.application;

import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.saga.SagaOutcome;
import com.malefashion.order.domain.saga.SagaStateMachine;
import com.malefashion.order.domain.saga.SagaTransition;
import com.malefashion.order.domain.support.MoneyAmounts;
import com.malefashion.order.infrastructure.messaging.InboundEvent;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.PaymentFailed;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.PaymentRefunded;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.PaymentSucceeded;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockCommitFailed;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockCommitted;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockReleased;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockReservationFailed;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockReserved;
import com.malefashion.order.infrastructure.persistence.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Handler thật của 8 event saga. Chạy trong transaction của {@link InboxProcessor}: marker inbox,
 * thay đổi order và outbox cùng commit hoặc cùng rollback.
 *
 * <p>Order được nạp lại trong transaction hiện tại bằng khóa ghi để tuần tự hóa với các event khác
 * và với scheduler hạn chờ. Order không tồn tại, reservationId lệch hoặc event không áp dụng đều
 * được ack (không ném lỗi) vì retry không cứu được.</p>
 */
@Component
public class OrderSagaEventHandler implements SagaEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaEventHandler.class);

    private final OrderRepository orderRepository;
    private final SagaStateMachine sagaStateMachine;
    private final SagaActionExecutor actionExecutor;

    public OrderSagaEventHandler(OrderRepository orderRepository,
                                 SagaStateMachine sagaStateMachine,
                                 SagaActionExecutor actionExecutor) {
        this.orderRepository = orderRepository;
        this.sagaStateMachine = sagaStateMachine;
        this.actionExecutor = actionExecutor;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(InboundEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        Order order = orderRepository.findByIdForUpdate(event.orderId()).orElse(null);
        if (order == null) {
            // Retry không cứu được một order không tồn tại; ack để message rời queue.
            log.warn("Ignoring event for unknown order orderId={} eventId={} eventType={}",
                    event.orderId(), event.eventId(), event.eventType());
            return;
        }

        if (hasMismatchedReservation(order, event)) {
            log.warn("Ignoring event with mismatched reservationId orderId={} eventId={} "
                            + "eventType={} orderReservationId={} eventReservationId={}",
                    order.getId(), event.eventId(), event.eventType(), order.getReservationId(),
                    reservationIdOf(event.payload()));
            return;
        }

        if (event.payload() instanceof PaymentSucceeded payment
                && payment.amount().value().compareTo(order.getTotalAmount()) != 0) {
            log.warn("Payment amount differs from order total; processing anyway orderId={} "
                    + "eventId={} eventType={}", order.getId(), event.eventId(),
                    event.eventType());
        }

        SagaTransition transition = transition(order, event.payload());
        if (!transition.isApplied()) {
            logOutcome(event, order, transition.outcome());
            return;
        }

        orderRepository.save(order);
        actionExecutor.execute(transition.actions(), order);
    }

    private SagaTransition transition(Order order, SagaEventPayload payload) {
        return switch (payload) {
            case StockReserved reserved -> sagaStateMachine.onStockReserved(order,
                    reserved.reservationId(), reserved.expiresAt());
            case StockReservationFailed failed -> sagaStateMachine.onStockReservationFailed(order);
            case StockCommitted committed -> sagaStateMachine.onStockCommitted(order);
            case StockCommitFailed failed -> sagaStateMachine.onStockCommitFailed(order);
            case StockReleased released -> sagaStateMachine.onStockReleased(order, released.reason());
            case PaymentSucceeded succeeded -> sagaStateMachine.onPaymentSucceeded(order,
                    succeeded.paymentId(), MoneyAmounts.scale(succeeded.amount().value()),
                    succeeded.providerTransactionId());
            case PaymentFailed failed -> sagaStateMachine.onPaymentFailed(order);
            case PaymentRefunded refunded -> sagaStateMachine.onPaymentRefunded(order,
                    refunded.refundId());
        };
    }

    private boolean hasMismatchedReservation(Order order, InboundEvent event) {
        UUID eventReservationId = reservationIdOf(event.payload());
        return order.getReservationId() != null && eventReservationId != null
                && !order.getReservationId().equals(eventReservationId);
    }

    private UUID reservationIdOf(SagaEventPayload payload) {
        return switch (payload) {
            case StockCommitted committed -> committed.reservationId();
            case StockCommitFailed failed -> failed.reservationId();
            case StockReleased released -> released.reservationId();
            default -> null;
        };
    }

    private void logOutcome(InboundEvent event, Order order, SagaOutcome outcome) {
        if (outcome == SagaOutcome.IGNORED_DUPLICATE) {
            log.debug("Ignoring duplicate event orderId={} eventId={} eventType={} status={} "
                            + "step={}", order.getId(), event.eventId(), event.eventType(),
                    order.getStatus(), order.getSagaStep());
        } else {
            log.warn("Ignoring non-applicable event orderId={} eventId={} eventType={} status={} "
                            + "step={}", order.getId(), event.eventId(), event.eventType(),
                    order.getStatus(), order.getSagaStep());
        }
    }
}
