package com.malefashion.order.infrastructure.messaging;

import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.saga.StockReleaseReason;
import com.malefashion.order.support.MutableClock;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.TestOrderProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderMessageFactoryTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:15:30.123456Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final Order order = OrderFixtures.newOrder(NOW);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void eventCarriesEnvelopeContractAndFreshEventIdEachTime() {
        OrderMessageFactory factory = factory(false);

        MessageEnvelope<OrderMessageFactory.OrderCreatedPayload> first =
                factory.orderCreated(order);
        MessageEnvelope<OrderMessageFactory.OrderCreatedPayload> second =
                factory.orderCreated(order);

        assertThat(first.eventId()).isNotEqualTo(second.eventId());
        assertThat(first.eventType()).isEqualTo(MessageTypes.EVENT_ORDER_CREATED);
        assertThat(first.eventVersion()).isEqualTo(1);
        assertThat(first.producer()).isEqualTo("order-service");
        assertThat(first.correlationId()).isEqualTo(order.getId());
        assertThat(first.occurredAt()).isEqualTo(Instant.parse("2026-10-22T03:15:30.123Z"));
        assertThat(first.payload().orderId()).isEqualTo(order.getId());
        assertThat(first.payload().orderNumber()).isEqualTo(OrderFixtures.ORDER_NUMBER);
        assertThat(first.payload().createdAt()).isEqualTo(order.getCreatedAt());
        assertThat(first.payload().totalAmount().amount()).isEqualTo("578000.00");
        assertThat(first.payload().items()).hasSize(1);
        assertThat(first.payload().items().getFirst().unitPrice().amount())
                .isEqualTo("299000.00");
    }

    @Test
    void commandUsesProvidedEventIdAndOrderIdAsCorrelationId() {
        OrderMessageFactory factory = factory(false);
        UUID eventId = UUID.randomUUID();

        MessageEnvelope<OrderMessageFactory.ReserveStockPayload> envelope =
                factory.reserveStock(eventId, order);

        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.eventType()).isEqualTo(MessageTypes.COMMAND_RESERVE_STOCK);
        assertThat(envelope.correlationId()).isEqualTo(order.getId());
        assertThat(envelope.payload().items()).hasSize(1);
        assertThat(envelope.payload().items().getFirst().quantity()).isEqualTo(2);
    }

    @Test
    void eventUsesValidTraceIdFromMdc() {
        MDC.put(TraceIds.MDC_TRACE_ID, TestOrderProperties.TRACE_ID);
        OrderMessageFactory factory = factory(false);

        MessageEnvelope<OrderMessageFactory.OrderConfirmedPayload> envelope =
                factory.orderConfirmed(order);

        assertThat(envelope.traceId()).isEqualTo(TestOrderProperties.TRACE_ID);
    }

    @Test
    void eventUsesFreshTraceIdWhenMdcIsInvalid() {
        MDC.put(TraceIds.MDC_TRACE_ID, "invalid");
        OrderMessageFactory factory = factory(false);

        assertThat(factory.orderConfirmed(order).traceId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void processPaymentIncludesMockScenarioOnlyWhenEnabled() {
        MessageEnvelope<OrderMessageFactory.ProcessPaymentPayload> enabled =
                factory(true).processPayment(UUID.randomUUID(), order);
        MessageEnvelope<OrderMessageFactory.ProcessPaymentPayload> disabled =
                factory(false).processPayment(UUID.randomUUID(), order);

        assertThat(enabled.payload().mockScenario()).isEqualTo("SUCCESS");
        assertThat(disabled.payload().mockScenario()).isNull();
        assertThat(enabled.payload().idempotencyKey()).isEqualTo(order.getId().toString());
        assertThat(enabled.payload().amount().amount()).isEqualTo("578000.00");
    }

    @Test
    void refundUsesActualPaidAmountAndContractReason() {
        order.applyPayment(OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID, NOW);
        OrderMessageFactory factory = factory(false);

        MessageEnvelope<OrderMessageFactory.RefundPaymentPayload> envelope =
                factory.refundPayment(UUID.randomUUID(), order);

        assertThat(envelope.payload().paymentId()).isEqualTo(OrderFixtures.PAYMENT_ID);
        assertThat(envelope.payload().amount().amount()).isEqualTo("578000.00");
        assertThat(envelope.payload().reason()).isEqualTo("STOCK_COMMIT_FAILED");
    }

    @Test
    void refundWithoutPaymentFailsFast() {
        OrderMessageFactory factory = factory(false);

        assertThatThrownBy(() -> factory.refundPayment(UUID.randomUUID(), order))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("paymentId");
    }

    @Test
    void releaseStockRejectsReservationExpiredReason() {
        OrderMessageFactory factory = factory(false);

        assertThatThrownBy(() -> factory.releaseStock(UUID.randomUUID(), order.getId(),
                OrderFixtures.RESERVATION_ID, StockReleaseReason.RESERVATION_EXPIRED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelledEventCarriesReasonAndClockTimestamp() {
        OrderMessageFactory factory = factory(false);

        MessageEnvelope<OrderMessageFactory.OrderCancelledPayload> envelope =
                factory.orderCancelled(order, CancelReason.PAYMENT_FAILED);

        assertThat(envelope.payload().reason()).isEqualTo("PAYMENT_FAILED");
        assertThat(envelope.payload().cancelledAt())
                .isEqualTo(Instant.parse("2026-10-22T03:15:30.123Z"));
    }

    private OrderMessageFactory factory(boolean mockScenarioEnabled) {
        return new OrderMessageFactory(TestOrderProperties.valid(mockScenarioEnabled), clock);
    }
}
