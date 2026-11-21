package com.malefashion.order.application;

import com.malefashion.order.domain.model.Order;

/**
 * Kết quả checkout; replayed=true nghĩa là trả lại đơn của cùng Idempotency-Key (HTTP 200).
 */
public record CheckoutOutcome(Order order, boolean replayed) {
}
