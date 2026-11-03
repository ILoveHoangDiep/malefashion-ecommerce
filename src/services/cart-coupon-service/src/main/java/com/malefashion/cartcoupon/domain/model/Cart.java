package com.malefashion.cartcoupon.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cart stored in Redis – plain POJO, NOT a JPA entity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cart implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private UUID cartId;

    private UUID userId;

    @Builder.Default
    private List<CartItem> items = new ArrayList<>();

    /** Coupon code currently applied to this cart (nullable). */
    private String appliedCouponCode;

    private Instant updatedAt;
}
