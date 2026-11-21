package com.malefashion.order.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Một dòng hàng đã lấy giá/thuộc tính từ catalog (source of truth), số lượng từ giỏ.
 */
public record CheckoutLine(
        UUID variantId,
        String sku,
        String productName,
        String size,
        String color,
        int quantity,
        BigDecimal unitPrice
) {
}
