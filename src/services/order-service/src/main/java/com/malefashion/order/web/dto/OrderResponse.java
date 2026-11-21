package com.malefashion.order.web.dto;

import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.OrderStatus;
import com.malefashion.order.domain.model.RefundState;
import com.malefashion.order.domain.model.SagaStep;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response đơn hàng theo order-api.yaml. Field chỉ dành cho ADMIN (sagaStep, commitUnconfirmed)
 * được để null với CUSTOMER và bị Jackson bỏ khỏi JSON.
 */
public record OrderResponse(
        UUID orderId,
        String orderNumber,
        OrderStatus status,
        CancelReason cancelReason,
        UUID userId,
        String currency,
        List<OrderItemResponse> items,
        MoneyResponse subtotal,
        MoneyResponse discountAmount,
        MoneyResponse shippingFee,
        MoneyResponse totalAmount,
        Instant createdAt,
        Instant updatedAt,
        ShippingAddressResponse shippingAddress,
        RefundState refundState,
        SagaStep sagaStep,
        Boolean commitUnconfirmed
) {

    public OrderResponse {
        items = List.copyOf(items);
    }
}
