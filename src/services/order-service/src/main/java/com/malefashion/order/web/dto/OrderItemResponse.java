package com.malefashion.order.web.dto;

import java.util.UUID;

public record OrderItemResponse(
        UUID variantId,
        String sku,
        String productName,
        String size,
        String color,
        int quantity,
        MoneyResponse unitPrice,
        MoneyResponse lineTotal
) {
}
