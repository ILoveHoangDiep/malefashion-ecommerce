package com.malefashion.cartcoupon.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Event published by order-service khi đơn hàng được xác nhận.
 * Routing key: order.confirmed
 * Exchange   : malefashion.events (topic)
 * Queue      : cart-coupon-service.events
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderConfirmedEvent(
        String eventId,
        String eventType,
        String traceId,
        Instant occurredAt,
        Payload payload
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(
            UUID orderId,
            String orderNumber,
            UUID userId,
            String userEmail,
            String customerName,
            String couponCode,       // nullable
            List<OrderItem> items,
            String totalAmount,
            Instant confirmedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderItem(
            UUID variantId,
            String sku,
            String productName,
            int quantity,
            String unitPrice
    ) {}
}
