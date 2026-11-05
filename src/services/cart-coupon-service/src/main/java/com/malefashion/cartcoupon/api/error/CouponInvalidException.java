package com.malefashion.cartcoupon.api.error;

import lombok.Getter;

@Getter
public class CouponInvalidException extends RuntimeException {

    private final String couponCode;

    public CouponInvalidException(String message, String couponCode) {
        super(message);
        this.couponCode = couponCode;
    }
}
