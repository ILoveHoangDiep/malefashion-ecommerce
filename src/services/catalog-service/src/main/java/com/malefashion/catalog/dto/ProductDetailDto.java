package com.malefashion.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductDetailDto {
    private UUID productId;
    private String name;
    private String slug;
    private String description;
    private String categoryName;
    @Builder.Default
    private List<VariantResponse> variants = new ArrayList<>();
}
