package com.malefashion.cartcoupon.dto;

import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateQuantityRequest {

    @Min(value = 0, message = "quantity không được âm")
    private int quantity;
}
