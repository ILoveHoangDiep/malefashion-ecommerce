package com.malefashion.cartcoupon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.cartcoupon.api.error.CartNotFoundException;
import com.malefashion.cartcoupon.domain.model.Cart;
import com.malefashion.cartcoupon.domain.model.CartItem;
import com.malefashion.cartcoupon.dto.AddToCartRequest;
import com.malefashion.cartcoupon.dto.CartResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private CouponService couponService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private CartService cartService;

    private UUID userId;
    private Cart existingCart;
    private UUID variantId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CartItem item = CartItem.builder()
                .variantId(variantId)
                .sku("SHIRT-01")
                .productName("Oxford Shirt")
                .size("M")
                .color("White")
                .unitPrice(new BigDecimal("300000.00"))
                .quantity(1)
                .build();

        existingCart = Cart.builder()
                .cartId(UUID.randomUUID())
                .userId(userId)
                .items(new ArrayList<>(java.util.List.of(item)))
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("getCart returns null when cart does not exist in Redis")
    void testGetCart_NotFound() {
        when(valueOperations.get("cart:" + userId)).thenReturn(null);

        Cart cart = cartService.getCart(userId);

        assertThat(cart).isNull();
    }

    @Test
    @DisplayName("addItem adds new item and calculates subtotal correctly")
    void testAddItem_NewItem() {
        when(valueOperations.get("cart:" + userId)).thenReturn(existingCart);

        UUID newVariantId = UUID.randomUUID();
        AddToCartRequest req = AddToCartRequest.builder()
                .variantId(newVariantId)
                .sku("PANTS-01")
                .productName("Chino Pants")
                .size("32")
                .color("Beige")
                .unitPrice(new BigDecimal("400000.00"))
                .quantity(2)
                .build();

        CartResponse response = cartService.addItem(userId, req);

        assertThat(response.getItems()).hasSize(2);
        // 300,000 * 1 + 400,000 * 2 = 1,100,000
        assertThat(response.getSubtotal().getAmount()).isEqualTo("1100000.00");
        assertThat(response.getTotalAmount().getAmount()).isEqualTo("1100000.00");
    }

    @Test
    @DisplayName("addItem increments quantity when variant already exists in cart")
    void testAddItem_ExistingItem() {
        when(valueOperations.get("cart:" + userId)).thenReturn(existingCart);

        AddToCartRequest req = AddToCartRequest.builder()
                .variantId(variantId)
                .sku("SHIRT-01")
                .productName("Oxford Shirt")
                .size("M")
                .color("White")
                .unitPrice(new BigDecimal("300000.00"))
                .quantity(3)
                .build();

        CartResponse response = cartService.addItem(userId, req);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(4);
        assertThat(response.getSubtotal().getAmount()).isEqualTo("1200000.00");
    }

    @Test
    @DisplayName("updateQuantity updates item quantity and lineTotal")
    void testUpdateQuantity_Positive() {
        when(valueOperations.get("cart:" + userId)).thenReturn(existingCart);

        CartResponse response = cartService.updateQuantity(userId, variantId, 3);

        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(3);
        assertThat(response.getSubtotal().getAmount()).isEqualTo("900000.00");
    }

    @Test
    @DisplayName("updateQuantity removes item when quantity is 0 or negative")
    void testUpdateQuantity_Zero() {
        when(valueOperations.get("cart:" + userId)).thenReturn(existingCart);

        CartResponse response = cartService.updateQuantity(userId, variantId, 0);

        assertThat(response.getItems()).isEmpty();
        assertThat(response.getSubtotal().getAmount()).isEqualTo("0.00");
    }

    @Test
    @DisplayName("removeItem removes item from cart")
    void testRemoveItem() {
        when(valueOperations.get("cart:" + userId)).thenReturn(existingCart);

        CartResponse response = cartService.removeItem(userId, variantId);

        assertThat(response.getItems()).isEmpty();
    }

    @Test
    @DisplayName("clearCart deletes the redis key")
    void testClearCart() {
        cartService.clearCart(userId);

        verify(redisTemplate).delete("cart:" + userId);
    }

    @Test
    @DisplayName("applyCoupon applies discount to cart")
    void testApplyCoupon() {
        when(valueOperations.get("cart:" + userId)).thenReturn(existingCart);
        when(couponService.validateAndCalculateDiscount(eq("DISCOUNT10"), any(BigDecimal.class), eq(userId)))
                .thenReturn(new BigDecimal("30000.00"));

        CartResponse response = cartService.applyCoupon(userId, "DISCOUNT10");

        assertThat(response.getCoupon()).isNotNull();
        assertThat(response.getCoupon().getCode()).isEqualTo("DISCOUNT10");
        assertThat(response.getDiscountAmount().getAmount()).isEqualTo("30000.00");
        // 300,000 - 30,000 = 270,000
        assertThat(response.getTotalAmount().getAmount()).isEqualTo("270000.00");
    }

    @Test
    @DisplayName("removeCoupon resets discount amount to 0")
    void testRemoveCoupon() {
        existingCart.setAppliedCouponCode("DISCOUNT10");
        when(valueOperations.get("cart:" + userId)).thenReturn(existingCart);

        CartResponse response = cartService.removeCoupon(userId);

        assertThat(response.getCoupon()).isNull();
        assertThat(response.getDiscountAmount().getAmount()).isEqualTo("0.00");
        assertThat(response.getTotalAmount().getAmount()).isEqualTo("300000.00");
    }
}
