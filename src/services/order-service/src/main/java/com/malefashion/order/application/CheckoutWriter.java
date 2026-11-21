package com.malefashion.order.application;

import com.malefashion.order.config.OrderProperties;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.support.Instants;
import com.malefashion.order.domain.support.OrderNumberGenerator;
import com.malefashion.order.infrastructure.messaging.OrderMessageFactory;
import com.malefashion.order.infrastructure.persistence.CheckoutIdempotency;
import com.malefashion.order.infrastructure.persistence.CheckoutIdempotencyRepository;
import com.malefashion.order.infrastructure.persistence.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * MỘT transaction cho order + order_items + idempotency + 2 dòng outbox (order.created và
 * inventory.reserve-stock). Mọi lỗi ở bất kỳ bước nào đều rollback toàn bộ. HTTP call tới cart và
 * catalog đã xong trước khi method này chạy.
 */
@Service
public class CheckoutWriter {

    private final OrderRepository orderRepository;
    private final CheckoutIdempotencyRepository idempotencyRepository;
    private final OutboxService outboxService;
    private final OrderMessageFactory messageFactory;
    private final OrderNumberGenerator orderNumberGenerator;
    private final OrderProperties properties;
    private final Clock clock;

    public CheckoutWriter(OrderRepository orderRepository,
                          CheckoutIdempotencyRepository idempotencyRepository,
                          OutboxService outboxService,
                          OrderMessageFactory messageFactory,
                          OrderNumberGenerator orderNumberGenerator,
                          OrderProperties properties,
                          Clock clock) {
        this.orderRepository = orderRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.outboxService = outboxService;
        this.messageFactory = messageFactory;
        this.orderNumberGenerator = orderNumberGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Order create(CheckoutDraft draft) {
        Instant now = Instants.millis(clock.instant());
        Instant sagaDeadlineAt = Instants.millis(now.plus(properties.saga().timeout()));

        Order order = Order.create(
                UUID.randomUUID(),
                orderNumberGenerator.next(),
                draft.userId(),
                draft.userEmail(),
                draft.shipping(),
                draft.money(),
                draft.couponCode(),
                draft.mockPaymentScenario(),
                sagaDeadlineAt,
                now);
        for (CheckoutLine line : draft.lines()) {
            order.addItem(line.variantId(), line.sku(), line.productName(), line.size(),
                    line.color(), line.quantity(), line.unitPrice());
        }

        // Flush order trước để FK của checkout_idempotency luôn thấy dòng orders.
        orderRepository.saveAndFlush(order);
        idempotencyRepository.saveAndFlush(CheckoutIdempotency.create(
                UUID.randomUUID(), draft.userId(), draft.idempotencyKey(), draft.requestHash(),
                order.getId(), now));

        outboxService.appendEvent(messageFactory.orderCreated(order));
        outboxService.appendCommand(messageFactory.reserveStock(UUID.randomUUID(), order));
        return order;
    }
}
