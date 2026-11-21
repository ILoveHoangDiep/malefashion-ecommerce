package com.malefashion.order.infrastructure.messaging;

import com.malefashion.order.config.OrderProperties;
import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.saga.StockReleaseReason;
import com.malefashion.order.domain.support.Instants;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Tạo envelope + payload cho 5 command saga gửi và 3 event order-service phát.
 *
 * <p>Command nhận {@code eventId} từ caller vì handler phải giữ nguyên eventId khi gửi lại (retry
 * refund). Event tự sinh eventId mới mỗi lần phát. {@code occurredAt} luôn cắt về mili giây;
 * {@code traceId} lấy từ MDC nếu hợp lệ, nếu không thì sinh mới.</p>
 */
@Component
public class OrderMessageFactory {

    public static final String PRODUCER = "order-service";
    public static final int EVENT_VERSION = 1;

    private final OrderProperties properties;
    private final Clock clock;

    public OrderMessageFactory(OrderProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public MessageEnvelope<OrderCreatedPayload> orderCreated(Order order) {
        List<OrderCreatedItem> items = order.getItems().stream()
                .map(item -> new OrderCreatedItem(item.getVariantId(), item.getSku(),
                        item.getProductName(), item.getSize(), item.getColor(), item.getQuantity(),
                        Money.vnd(item.getUnitPrice()), Money.vnd(item.getLineTotal())))
                .toList();
        return envelope(MessageTypes.EVENT_ORDER_CREATED, UUID.randomUUID(), order.getId(),
                new OrderCreatedPayload(
                        order.getId(),
                        order.getOrderNumber(),
                        order.getUserId(),
                        order.getUserEmail(),
                        order.getCustomerName(),
                        order.getCurrency(),
                        items,
                        Money.vnd(order.getSubtotal()),
                        Money.vnd(order.getDiscountAmount()),
                        Money.vnd(order.getShippingFee()),
                        Money.vnd(order.getTotalAmount()),
                        order.getCreatedAt()));
    }

    public MessageEnvelope<OrderConfirmedPayload> orderConfirmed(Order order) {
        List<OrderConfirmedItem> items = order.getItems().stream()
                .map(item -> new OrderConfirmedItem(item.getVariantId(), item.getSku(),
                        item.getProductName(), item.getSize(), item.getColor(), item.getQuantity(),
                        Money.vnd(item.getLineTotal())))
                .toList();
        return envelope(MessageTypes.EVENT_ORDER_CONFIRMED, UUID.randomUUID(), order.getId(),
                new OrderConfirmedPayload(
                        order.getId(),
                        order.getOrderNumber(),
                        order.getUserId(),
                        order.getUserEmail(),
                        order.getCustomerName(),
                        order.getCurrency(),
                        items,
                        Money.vnd(order.getTotalAmount()),
                        now()));
    }

    public MessageEnvelope<OrderCancelledPayload> orderCancelled(Order order,
                                                                 CancelReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        return envelope(MessageTypes.EVENT_ORDER_CANCELLED, UUID.randomUUID(), order.getId(),
                new OrderCancelledPayload(
                        order.getId(),
                        order.getOrderNumber(),
                        order.getUserId(),
                        order.getUserEmail(),
                        order.getCustomerName(),
                        reason.name(),
                        now()));
    }

    public MessageEnvelope<ReserveStockPayload> reserveStock(UUID eventId, Order order) {
        List<ReserveStockItem> items = order.getItems().stream()
                .map(item -> new ReserveStockItem(item.getVariantId(), item.getSku(),
                        item.getQuantity()))
                .toList();
        return envelope(MessageTypes.COMMAND_RESERVE_STOCK, eventId, order.getId(),
                new ReserveStockPayload(order.getId(), items));
    }

    public MessageEnvelope<CommitStockPayload> commitStock(UUID eventId,
                                                           UUID orderId,
                                                           UUID reservationId) {
        return envelope(MessageTypes.COMMAND_COMMIT_STOCK, eventId, orderId,
                new CommitStockPayload(orderId, reservationId));
    }

    public MessageEnvelope<ReleaseStockPayload> releaseStock(UUID eventId,
                                                             UUID orderId,
                                                             UUID reservationId,
                                                             StockReleaseReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason == StockReleaseReason.RESERVATION_EXPIRED) {
            throw new IllegalArgumentException(
                    "inventory.release-stock does not accept RESERVATION_EXPIRED");
        }
        return envelope(MessageTypes.COMMAND_RELEASE_STOCK, eventId, orderId,
                new ReleaseStockPayload(orderId, reservationId, reason.name()));
    }

    public MessageEnvelope<ProcessPaymentPayload> processPayment(UUID eventId, Order order) {
        String mockScenario = properties.mockScenarioEnabled()
                ? order.getMockPaymentScenario()
                : null;
        return envelope(MessageTypes.COMMAND_PROCESS_PAYMENT, eventId, order.getId(),
                new ProcessPaymentPayload(
                        order.getId(),
                        Money.vnd(order.getTotalAmount()),
                        order.getId().toString(),
                        mockScenario));
    }

    public MessageEnvelope<RefundPaymentPayload> refundPayment(UUID eventId, Order order) {
        UUID paymentId = Objects.requireNonNull(order.getPaymentId(),
                "order.paymentId must be set before refund");
        BigDecimal paidAmount = Objects.requireNonNull(order.getPaidAmount(),
                "order.paidAmount must be set before refund");
        return envelope(MessageTypes.COMMAND_REFUND_PAYMENT, eventId, order.getId(),
                new RefundPaymentPayload(order.getId(), paymentId, Money.vnd(paidAmount),
                        CancelReason.STOCK_COMMIT_FAILED.name()));
    }

    private <T> MessageEnvelope<T> envelope(String eventType,
                                            UUID eventId,
                                            UUID orderId,
                                            T payload) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        return new MessageEnvelope<>(
                eventId,
                eventType,
                EVENT_VERSION,
                now(),
                PRODUCER,
                Objects.requireNonNull(orderId, "orderId must not be null"),
                TraceIds.currentOrNew(),
                payload);
    }

    private Instant now() {
        return Instants.millis(clock.instant());
    }

    public record OrderCreatedPayload(
            UUID orderId,
            String orderNumber,
            UUID userId,
            String userEmail,
            String customerName,
            String currency,
            List<OrderCreatedItem> items,
            Money subtotal,
            Money discountAmount,
            Money shippingFee,
            Money totalAmount,
            Instant createdAt
    ) {
        public OrderCreatedPayload {
            items = List.copyOf(items);
        }
    }

    public record OrderCreatedItem(
            UUID variantId,
            String sku,
            String productName,
            String size,
            String color,
            int quantity,
            Money unitPrice,
            Money lineTotal
    ) {
    }

    public record OrderConfirmedPayload(
            UUID orderId,
            String orderNumber,
            UUID userId,
            String userEmail,
            String customerName,
            String currency,
            List<OrderConfirmedItem> items,
            Money totalAmount,
            Instant confirmedAt
    ) {
        public OrderConfirmedPayload {
            items = List.copyOf(items);
        }
    }

    public record OrderConfirmedItem(
            UUID variantId,
            String sku,
            String productName,
            String size,
            String color,
            int quantity,
            Money lineTotal
    ) {
    }

    public record OrderCancelledPayload(
            UUID orderId,
            String orderNumber,
            UUID userId,
            String userEmail,
            String customerName,
            String reason,
            Instant cancelledAt
    ) {
    }

    public record ReserveStockPayload(UUID orderId, List<ReserveStockItem> items) {
        public ReserveStockPayload {
            items = List.copyOf(items);
        }
    }

    public record ReserveStockItem(UUID variantId, String sku, int quantity) {
    }

    public record CommitStockPayload(UUID orderId, UUID reservationId) {
    }

    public record ReleaseStockPayload(UUID orderId, UUID reservationId, String reason) {
    }

    public record ProcessPaymentPayload(
            UUID orderId,
            Money amount,
            String idempotencyKey,
            String mockScenario
    ) {
    }

    public record RefundPaymentPayload(
            UUID orderId,
            UUID paymentId,
            Money amount,
            String reason
    ) {
    }
}
