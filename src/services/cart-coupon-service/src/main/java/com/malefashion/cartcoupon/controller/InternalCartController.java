package com.malefashion.cartcoupon.controller;

import com.malefashion.cartcoupon.api.error.CartEmptyException;
import com.malefashion.cartcoupon.api.error.CartNotFoundException;
import com.malefashion.cartcoupon.domain.model.Cart;
import com.malefashion.cartcoupon.dto.CartResponse;
import com.malefashion.cartcoupon.service.CartService;
import com.malefashion.cartcoupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/carts")
@RequiredArgsConstructor
@Slf4j
public class InternalCartController {

    private final CartService cartService;
    private final CouponService couponService;

    @GetMapping("/{userId}")
    public ResponseEntity<CartResponse> getCartForCheckout(
            @PathVariable UUID userId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "X-Caller-Service", required = false) String callerService) {

        log.debug("Internal getCartForCheckout: userId={}, traceId={}, caller={}", userId, traceId, callerService);

        Cart cart = cartService.getCart(userId);
        if (cart == null) {
            throw new CartNotFoundException("User chưa có giỏ hàng.", userId);
        }

        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new CartEmptyException("Giỏ hàng không có sản phẩm.", userId);
        }

        if (cart.getAppliedCouponCode() != null && !cart.getAppliedCouponCode().isBlank()) {
            BigDecimal subtotal = cart.getItems().stream()
                    .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // This will throw CouponInvalidException if invalid
            couponService.validateAndCalculateDiscount(cart.getAppliedCouponCode(), subtotal, userId);
        }

        CartResponse response = cartService.mapToCartResponse(cart);
        return ResponseEntity.ok(response);
    }
}
