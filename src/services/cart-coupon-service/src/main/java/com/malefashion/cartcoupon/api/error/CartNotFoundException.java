package com.malefashion.cartcoupon.api.error;

import lombok.Getter;

import java.util.UUID;

@Getter
public class CartNotFoundException extends RuntimeException {

    private final UUID userId;

    public CartNotFoundException(String message, UUID userId) {
        super(message);
        this.userId = userId;
    }
}
