package com.malefashion.cartcoupon.api.error;

import lombok.Getter;

import java.util.UUID;

@Getter
public class CartEmptyException extends RuntimeException {

    private final UUID userId;

    public CartEmptyException(String message, UUID userId) {
        super(message);
        this.userId = userId;
    }
}
