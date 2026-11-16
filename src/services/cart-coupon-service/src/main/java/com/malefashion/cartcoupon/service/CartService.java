package com.malefashion.cartcoupon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.cartcoupon.api.error.CartNotFoundException;
import com.malefashion.cartcoupon.domain.model.Cart;
import com.malefashion.cartcoupon.domain.model.CartItem;
import com.malefashion.cartcoupon.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartService {

    private static final String CART_KEY_PREFIX = "cart:";
    private static final Duration CART_TTL = Duration.ofDays(30);

    private final RedisTemplate<String, Object> redisTemplate;
    private final CouponService couponService;
    private final ObjectMapper objectMapper;

    public Cart getCart(UUID userId) {
        String key = buildCartKey(userId);
        Object raw = redisTemplate.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Cart cart) {
            return cart;
        }
        return objectMapper.convertValue(raw, Cart.class);
    }

    public Cart getCartOrCreate(UUID userId) {
        Cart cart = getCart(userId);
        if (cart == null) {
            cart = Cart.builder()
                    .cartId(UUID.randomUUID())
                    .userId(userId)
                    .items(new ArrayList<>())
                    .updatedAt(Instant.now())
                    .build();
            saveCart(cart);
        }
        return cart;
    }

    public CartResponse getCartResponse(UUID userId) {
        Cart cart = getCart(userId);
        if (cart == null) {
            throw new CartNotFoundException("User chưa có giỏ hàng.", userId);
        }
        return mapToCartResponse(cart);
    }

    public CartResponse addItem(UUID userId, AddToCartRequest req) {
        Cart cart = getCartOrCreate(userId);
        if (cart.getItems() == null) {
            cart.setItems(new ArrayList<>());
        }

        CartItem existingItem = cart.getItems().stream()
                .filter(item -> Objects.equals(item.getVariantId(), req.getVariantId()))
                .findFirst()
                .orElse(null);

        if (existingItem != null) {
            existingItem.setQuantity(existingItem.getQuantity() + req.getQuantity());
            existingItem.setUnitPrice(req.getUnitPrice());
            if (req.getImageUrl() != null && !req.getImageUrl().isBlank()) {
                existingItem.setImageUrl(req.getImageUrl());
            }
        } else {
            CartItem newItem = CartItem.builder()
                    .variantId(req.getVariantId())
                    .sku(req.getSku())
                    .productName(req.getProductName())
                    .size(req.getSize())
                    .color(req.getColor())
                    .imageUrl(req.getImageUrl())
                    .quantity(req.getQuantity())
                    .unitPrice(req.getUnitPrice())
                    .build();
            cart.getItems().add(newItem);
        }

        cart.setUpdatedAt(Instant.now());
        saveCart(cart);
        return mapToCartResponse(cart);
    }

    public CartResponse updateQuantity(UUID userId, UUID variantId, int quantity) {
        Cart cart = getCart(userId);
        if (cart == null) {
            throw new CartNotFoundException("User chưa có giỏ hàng.", userId);
        }

        if (quantity <= 0) {
            cart.getItems().removeIf(item -> Objects.equals(item.getVariantId(), variantId));
        } else {
            for (CartItem item : cart.getItems()) {
                if (Objects.equals(item.getVariantId(), variantId)) {
                    item.setQuantity(quantity);
                    break;
                }
            }
        }

        cart.setUpdatedAt(Instant.now());
        saveCart(cart);
        return mapToCartResponse(cart);
    }

    public CartResponse removeItem(UUID userId, UUID variantId) {
        Cart cart = getCart(userId);
        if (cart == null) {
            throw new CartNotFoundException("User chưa có giỏ hàng.", userId);
        }

        cart.getItems().removeIf(item -> Objects.equals(item.getVariantId(), variantId));
        cart.setUpdatedAt(Instant.now());
        saveCart(cart);
        return mapToCartResponse(cart);
    }

    public void clearCart(UUID userId) {
        String key = buildCartKey(userId);
        redisTemplate.delete(key);
    }

    public CartResponse applyCoupon(UUID userId, String couponCode) {
        Cart cart = getCartOrCreate(userId);
        BigDecimal subtotal = calculateSubtotal(cart);

        // Throws CouponInvalidException if invalid
        couponService.validateAndCalculateDiscount(couponCode, subtotal, userId);

        cart.setAppliedCouponCode(couponCode.trim().toUpperCase());
        cart.setUpdatedAt(Instant.now());
        saveCart(cart);
        return mapToCartResponse(cart);
    }

    public CartResponse removeCoupon(UUID userId) {
        Cart cart = getCart(userId);
        if (cart == null) {
            throw new CartNotFoundException("User chưa có giỏ hàng.", userId);
        }

        cart.setAppliedCouponCode(null);
        cart.setUpdatedAt(Instant.now());
        saveCart(cart);
        return mapToCartResponse(cart);
    }

    public void saveCart(Cart cart) {
        String key = buildCartKey(cart.getUserId());
        redisTemplate.opsForValue().set(key, cart, CART_TTL);
    }

    public CartResponse mapToCartResponse(Cart cart) {
        List<CartItemDto> itemDtos = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        if (cart.getItems() != null) {
            for (CartItem item : cart.getItems()) {
                BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
                BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
                subtotal = subtotal.add(lineTotal);

                itemDtos.add(CartItemDto.builder()
                        .variantId(item.getVariantId())
                        .sku(item.getSku())
                        .productName(item.getProductName())
                        .size(item.getSize())
                        .color(item.getColor())
                        .imageUrl(item.getImageUrl())
                        .quantity(item.getQuantity())
                        .unitPrice(MoneyDto.of(unitPrice))
                        .lineTotal(MoneyDto.of(lineTotal))
                        .build());
            }
        }

        BigDecimal discountAmount = BigDecimal.ZERO;
        AppliedCouponDto couponDto = null;

        if (cart.getAppliedCouponCode() != null && !cart.getAppliedCouponCode().isBlank()) {
            try {
                discountAmount = couponService.validateAndCalculateDiscount(
                        cart.getAppliedCouponCode(), subtotal, cart.getUserId());
                couponDto = AppliedCouponDto.builder()
                        .code(cart.getAppliedCouponCode())
                        .discountAmount(MoneyDto.of(discountAmount))
                        .build();
            } catch (Exception ex) {
                log.warn("Previously applied coupon {} became invalid: {}", cart.getAppliedCouponCode(), ex.getMessage());
                // Drop expired/invalid coupon
                cart.setAppliedCouponCode(null);
                saveCart(cart);
            }
        }

        BigDecimal totalAmount = subtotal.subtract(discountAmount).max(BigDecimal.ZERO);

        return CartResponse.builder()
                .cartId(cart.getCartId())
                .userId(cart.getUserId())
                .currency("VND")
                .items(itemDtos)
                .coupon(couponDto)
                .subtotal(MoneyDto.of(subtotal))
                .discountAmount(MoneyDto.of(discountAmount))
                .totalAmount(MoneyDto.of(totalAmount))
                .updatedAt(cart.getUpdatedAt() != null ? cart.getUpdatedAt() : Instant.now())
                .build();
    }

    private BigDecimal calculateSubtotal(Cart cart) {
        BigDecimal subtotal = BigDecimal.ZERO;
        if (cart.getItems() != null) {
            for (CartItem item : cart.getItems()) {
                BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
                subtotal = subtotal.add(unitPrice.multiply(BigDecimal.valueOf(item.getQuantity())));
            }
        }
        return subtotal;
    }

    private String buildCartKey(UUID userId) {
        return CART_KEY_PREFIX + userId;
    }
}
