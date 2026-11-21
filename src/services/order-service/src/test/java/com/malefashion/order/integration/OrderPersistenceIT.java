package com.malefashion.order.integration;

import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.OrderItem;
import com.malefashion.order.domain.model.OrderStatus;
import com.malefashion.order.domain.model.RefundState;
import com.malefashion.order.domain.model.SagaStep;
import com.malefashion.order.infrastructure.persistence.OrderRepository;
import com.malefashion.order.support.IntegrationTestBase;
import com.malefashion.order.support.OrderFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPersistenceIT extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-10-22T03:15:30.123Z");

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void savesAndReloadsOrderWithItemsAndRefundColumns() {
        Order order = OrderFixtures.newOrder(NOW);
        order.applyReservation(OrderFixtures.RESERVATION_ID,
                NOW.plus(Duration.ofMinutes(15)), NOW);
        order.applyPayment(OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID, NOW);
        order.moveToStep(SagaStep.AWAITING_REFUND, NOW);
        order.cancel(CancelReason.STOCK_COMMIT_FAILED, NOW);
        java.util.UUID refundEventId = java.util.UUID.randomUUID();
        order.startRefund(Duration.ofMinutes(2), NOW, refundEventId);
        order.increaseRefundRetryCount(NOW);
        order.setRefundDeadline(NOW.plus(Duration.ofMinutes(1)), NOW);

        orderRepository.saveAndFlush(order);
        Order reloaded = orderRepository.findWithItemsById(OrderFixtures.ORDER_ID).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reloaded.getCancelReason()).isEqualTo(CancelReason.STOCK_COMMIT_FAILED);
        assertThat(reloaded.getSagaStep()).isEqualTo(SagaStep.AWAITING_REFUND);
        assertThat(reloaded.getRefundState()).isEqualTo(RefundState.REQUESTED);
        assertThat(reloaded.getRefundCommandEventId()).isEqualTo(refundEventId);
        assertThat(reloaded.getRefundRetryCount()).isEqualTo(1);
        assertThat(reloaded.getRefundDeadlineAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(reloaded.getReservationId()).isEqualTo(OrderFixtures.RESERVATION_ID);
        assertThat(reloaded.getPaymentId()).isEqualTo(OrderFixtures.PAYMENT_ID);
        assertThat(reloaded.getPaidAmount()).isEqualByComparingTo(OrderFixtures.TOTAL);
        assertThat(reloaded.getProviderTransactionId())
                .isEqualTo(OrderFixtures.PROVIDER_TRANSACTION_ID);
        assertThat(reloaded.getUserEmail()).isEqualTo(OrderFixtures.USER_EMAIL);
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo(OrderFixtures.TOTAL);

        assertThat(reloaded.getItems()).hasSize(1);
        OrderItem item = reloaded.getItems().getFirst();
        assertThat(item.getVariantId()).isEqualTo(OrderFixtures.VARIANT_ID);
        assertThat(item.getSku()).isEqualTo("MF-TEE-BLACK-M");
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.getUnitPrice()).isEqualByComparingTo("299000.00");
        assertThat(item.getLineTotal()).isEqualByComparingTo("598000.00");

        reloaded.applyRefund(OrderFixtures.REFUND_ID, NOW.plusSeconds(30));
        orderRepository.saveAndFlush(reloaded);
        Order refunded = orderRepository.findWithItemsById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(refunded.getRefundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(refunded.getRefundId()).isEqualTo(OrderFixtures.REFUND_ID);
        assertThat(refunded.getRefundDeadlineAt()).isNull();
    }

    @Test
    void incrementsVersionOnUpdate() {
        orderRepository.saveAndFlush(OrderFixtures.newOrder(NOW));
        Order loaded = orderRepository.findWithItemsById(OrderFixtures.ORDER_ID).orElseThrow();
        long initialVersion = loaded.getVersion();

        loaded.applyReservation(OrderFixtures.RESERVATION_ID,
                NOW.plus(Duration.ofMinutes(15)), NOW);
        orderRepository.saveAndFlush(loaded);

        Order reloaded = orderRepository.findWithItemsById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(initialVersion + 1);
    }

    @Test
    void rejectsConcurrentUpdateOfSameVersion() {
        orderRepository.saveAndFlush(OrderFixtures.newOrder(NOW));
        Order first = orderRepository.findById(OrderFixtures.ORDER_ID).orElseThrow();
        Order second = orderRepository.findById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(second.getVersion()).isEqualTo(first.getVersion());

        first.applyReservation(OrderFixtures.RESERVATION_ID,
                NOW.plus(Duration.ofMinutes(15)), NOW);
        second.applyReservation(OrderFixtures.RESERVATION_ID,
                NOW.plus(Duration.ofMinutes(15)), NOW);
        orderRepository.saveAndFlush(first);

        assertThatThrownBy(() -> orderRepository.saveAndFlush(second))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void truncatesTimestampsToMilliseconds() {
        Instant withNanos = Instant.parse("2026-10-22T03:15:30.123456789Z");
        orderRepository.saveAndFlush(OrderFixtures.newOrder(withNanos));

        Order reloaded = orderRepository.findWithItemsById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(Instant.parse("2026-10-22T03:15:30.123Z"));
        assertThat(reloaded.getUpdatedAt().getNano() % 1_000_000).isZero();

        Instant updateNanos = Instant.parse("2026-10-22T03:16:30.987654321Z");
        reloaded.applyReservation(OrderFixtures.RESERVATION_ID,
                updateNanos.plus(Duration.ofMinutes(15)), updateNanos);
        orderRepository.saveAndFlush(reloaded);

        Order updated = orderRepository.findWithItemsById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(updated.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-22T03:16:30.987Z"));
        assertThat(updated.getReservationExpiresAt().getNano() % 1_000_000).isZero();
    }
}
