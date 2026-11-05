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
public class ProductSummaryDto {
    private UUID productId;
    private String name;
    private String slug;
    private String categoryName;
    private MoneyDto minPrice;
    private MoneyDto maxPrice;
    private String primaryImageUrl;
    private int variantCount;
}
