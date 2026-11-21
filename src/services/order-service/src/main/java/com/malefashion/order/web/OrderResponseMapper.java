package com.malefashion.order.web;

import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.OrderItem;
import com.malefashion.order.domain.model.OrderStatus;
import com.malefashion.order.web.dto.MoneyResponse;
import com.malefashion.order.web.dto.OrderItemResponse;
import com.malefashion.order.web.dto.OrderResponse;
import com.malefashion.order.web.dto.ShippingAddressResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Chuyển entity sang response contract. Trạng thái saga và cờ commit_unconfirmed chỉ lộ cho ADMIN.
 */
@Component
public class OrderResponseMapper {

    public OrderResponse toResponse(Order order, boolean admin) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(this::toItem)
                .toList();
        CancelReason cancelReason = order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.FAILED ? order.getCancelReason() : null;
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                cancelReason,
                order.getUserId(),
                order.getCurrency(),
                items,
                MoneyResponse.vnd(order.getSubtotal()),
                MoneyResponse.vnd(order.getDiscountAmount()),
                MoneyResponse.vnd(order.getShippingFee()),
                MoneyResponse.vnd(order.getTotalAmount()),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                new ShippingAddressResponse(
                        order.getShippingRecipientName(),
                        order.getShippingPhone(),
                        order.getShippingAddressLine(),
                        order.getShippingWard(),
                        order.getShippingDistrict(),
                        order.getShippingCity()),
                order.getRefundState(),
                admin ? order.getSagaStep() : null,
                admin ? order.isCommitUnconfirmed() : null);
    }

    private OrderItemResponse toItem(OrderItem item) {
        return new OrderItemResponse(
                item.getVariantId(),
                item.getSku(),
                item.getProductName(),
                item.getSize(),
                item.getColor(),
                item.getQuantity(),
                MoneyResponse.vnd(item.getUnitPrice()),
                MoneyResponse.vnd(item.getLineTotal()));
    }
}
