package com.malefashion.cartcoupon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartResponse {
    private UUID cartId;
    private UUID userId;
    @Builder.Default
    private String currency = "VND";
    @Builder.Default
    private List<CartItemDto> items = new ArrayList<>();
    private AppliedCouponDto coupon;
    private MoneyDto subtotal;
    private MoneyDto discountAmount;
    private MoneyDto totalAmount;
    private Instant updatedAt;
}
