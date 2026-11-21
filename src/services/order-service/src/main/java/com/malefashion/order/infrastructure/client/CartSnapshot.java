package com.malefashion.order.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Snapshot giỏ + coupon theo cart-coupon-api.yaml. Giá ở đây chỉ để đối chiếu; order dùng giá
 * catalog làm source of truth.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CartSnapshot(
        UUID cartId,
        UUID userId,
        String currency,
        List<CartItem> items,
        AppliedCoupon coupon,
        UpstreamMoney subtotal,
        UpstreamMoney discountAmount,
        UpstreamMoney totalAmount,
        Instant updatedAt
) {

    public CartSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CartItem(
            UUID variantId,
            String sku,
            String productName,
            String size,
            String color,
            String imageUrl,
            Integer quantity,
            UpstreamMoney unitPrice,
            UpstreamMoney lineTotal
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AppliedCoupon(String code, UpstreamMoney discountAmount) {
    }
}
