package com.malefashion.cartcoupon.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddToCartRequest {

    @NotNull(message = "variantId không được để trống")
    private UUID variantId;

    @NotBlank(message = "sku không được để trống")
    private String sku;

    @NotBlank(message = "productName không được để trống")
    private String productName;

    @NotBlank(message = "size không được để trống")
    private String size;

    @NotBlank(message = "color không được để trống")
    private String color;

    private String imageUrl;

    @NotNull(message = "unitPrice không được để trống")
    @DecimalMin(value = "0.00", message = "unitPrice phải lớn hơn hoặc bằng 0")
    private BigDecimal unitPrice;

    @Min(value = 1, message = "quantity tối thiểu là 1")
    private int quantity;
}
