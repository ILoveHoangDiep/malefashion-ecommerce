package com.malefashion.order.application;

import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.saga.SagaStateMachine;
import com.malefashion.order.domain.saga.SagaTransition;
import com.malefashion.order.infrastructure.persistence.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Một lần xử lý hạn chờ trên MỘT order, trong MỘT transaction. Scheduler giai đoạn 4 chỉ cần truy
 * vấn id các đơn quá hạn (dùng partial index) rồi gọi các method này; mỗi method khóa ghi dòng order
 * và kiểm tra lại hạn/trạng thái nên gọi trùng hoặc gọi khi trạng thái đã đổi đều an toàn.
 *
 * <p>Không đặt sẵn REQUIRES_NEW: method tự mở transaction khi scheduler gọi, và dùng OutboxService
 * (MANDATORY) để outbox cùng commit với thay đổi order.</p>
 */
@Service
public class OrderDeadlineService {

    private static final Logger log = LoggerFactory.getLogger(OrderDeadlineService.class);

    private final OrderRepository orderRepository;
    private final SagaStateMachine sagaStateMachine;
    private final SagaActionExecutor actionExecutor;

    public OrderDeadlineService(OrderRepository orderRepository,
                                SagaStateMachine sagaStateMachine,
                                SagaActionExecutor actionExecutor) {
        this.orderRepository = orderRepository;
        this.sagaStateMachine = sagaStateMachine;
        this.actionExecutor = actionExecutor;
    }

    /**
     * Dòng 5/6: hết hạn chờ reservation hoặc payment.
     */
    @Transactional
    public boolean onSagaDeadline(UUID orderId) {
        return apply(orderId, sagaStateMachine::onSagaTimeout);
    }

    /**
     * Dòng 14a: quá hạn chờ stock-released.
     */
    @Transactional
    public boolean onCompensationDeadline(UUID orderId) {
        return apply(orderId, sagaStateMachine::onCompensationTimeout);
    }

    /**
     * Dòng 14b/14c: quá hạn chờ payment.refunded.
     */
    @Transactional
    public boolean onRefundDeadline(UUID orderId) {
        return apply(orderId, sagaStateMachine::onRefundTimeout);
    }

    /**
     * Dòng 17a/17b: quá hạn chờ inventory.commit-stock.
     */
    @Transactional
    public boolean onCommitDeadline(UUID orderId) {
        return apply(orderId, sagaStateMachine::onCommitTimeout);
    }

    private boolean apply(UUID orderId, Function<Order, SagaTransition> decision) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Optional<Order> found = orderRepository.findByIdForUpdate(orderId);
        if (found.isEmpty()) {
            log.warn("Deadline check skipped for unknown order orderId={}", orderId);
            return false;
        }
        Order order = found.get();
        SagaTransition transition = decision.apply(order);
        if (!transition.isApplied()) {
            return false;
        }
        orderRepository.save(order);
        actionExecutor.execute(transition.actions(), order);
        return true;
    }
}
