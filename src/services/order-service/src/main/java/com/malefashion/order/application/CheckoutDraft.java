package com.malefashion.order.application;

import com.malefashion.order.domain.model.Order;

import java.util.List;
import java.util.UUID;

/**
 * Dữ liệu đã validate và tính tiền xong, sẵn sàng ghi trong MỘT transaction checkout. Mọi lời gọi
 * HTTP đã hoàn tất trước khi draft được tạo.
 */
public record CheckoutDraft(
        UUID userId,
        String userEmail,
        UUID idempotencyKey,
        String requestHash,
        Order.ShippingSnapshot shipping,
        Order.MoneySnapshot money,
        String couponCode,
        String mockPaymentScenario,
        List<CheckoutLine> lines
) {

    public CheckoutDraft {
        lines = List.copyOf(lines);
    }
}
