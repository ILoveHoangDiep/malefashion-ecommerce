package com.malefashion.cartcoupon.controller;

import com.malefashion.cartcoupon.dto.*;
import com.malefashion.cartcoupon.service.CartService;
import com.malefashion.cartcoupon.service.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
@Slf4j
public class StorefrontCartController {

    private final CartService cartService;
    private final CouponService couponService;

    @GetMapping
    public ResponseEntity<CartResponse> getCart(
            @RequestHeader("X-User-Id") UUID userId) {
        CartResponse response = cartService.getCartResponse(userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody AddToCartRequest req) {
        CartResponse response = cartService.addItem(userId, req);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/items/{variantId}")
    public ResponseEntity<CartResponse> updateQuantity(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID variantId,
            @Valid @RequestBody UpdateQuantityRequest req) {
        CartResponse response = cartService.updateQuantity(userId, variantId, req.getQuantity());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/items/{variantId}")
    public ResponseEntity<CartResponse> removeItem(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID variantId) {
        CartResponse response = cartService.removeItem(userId, variantId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    public ResponseEntity<Void> clearCart(
            @RequestHeader("X-User-Id") UUID userId) {
        cartService.clearCart(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/coupons")
    public ResponseEntity<CartResponse> applyCoupon(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody ApplyCouponRequest req) {
        CartResponse response = cartService.applyCoupon(userId, req.getCode());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/coupons")
    public ResponseEntity<CartResponse> removeCoupon(
            @RequestHeader("X-User-Id") UUID userId) {
        CartResponse response = cartService.removeCoupon(userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/coupons")
    public ResponseEntity<List<CouponDto>> getAvailableCoupons() {
        List<CouponDto> coupons = couponService.getAvailableCoupons();
        return ResponseEntity.ok(coupons);
    }
}
