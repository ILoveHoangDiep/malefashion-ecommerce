package com.malefashion.cartcoupon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppliedCouponDto {
    private String code;
    private MoneyDto discountAmount;
}
