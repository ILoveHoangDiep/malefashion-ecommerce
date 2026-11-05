package com.malefashion.cartcoupon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItemDto {
    private UUID variantId;
    private String sku;
    private String productName;
    private String size;
    private String color;
    private String imageUrl;
    private int quantity;
    private MoneyDto unitPrice;
    private MoneyDto lineTotal;
}
