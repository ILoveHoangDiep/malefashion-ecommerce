package com.malefashion.cartcoupon.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Single item inside a {@link Cart} – stored in Redis.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private UUID variantId;

    private String sku;

    private String productName;

    private String size;

    private String color;

    private String imageUrl;

    private int quantity;

    private BigDecimal unitPrice;
}
