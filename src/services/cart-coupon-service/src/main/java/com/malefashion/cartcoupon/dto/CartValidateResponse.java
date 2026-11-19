package com.malefashion.cartcoupon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartValidateResponse {

    private boolean valid;
    private String reason;
    private CartResponse cart;

    public static CartValidateResponse ok(CartResponse cart) {
        return CartValidateResponse.builder()
                .valid(true)
                .cart(cart)
                .build();
    }

    public static CartValidateResponse failed(String reason) {
        return CartValidateResponse.builder()
                .valid(false)
                .reason(reason)
                .build();
    }
}
