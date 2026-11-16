package com.malefashion.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VariantResponse {
    private UUID variantId;
    private String sku;
    private UUID productId;
    private String productName;
    private MoneyDto price;
    private VariantAttributesDto attributes;
    private String imageUrl;
    private boolean active;
}
