package com.malefashion.cartcoupon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.cartcoupon.api.error.GlobalExceptionHandler;
import com.malefashion.cartcoupon.dto.*;
import com.malefashion.cartcoupon.service.CartService;
import com.malefashion.cartcoupon.service.CouponService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StorefrontCartController.class)
@Import(GlobalExceptionHandler.class)
class StorefrontCartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CartService cartService;

    @MockBean
    private CouponService couponService;

    @Test
    @DisplayName("GET /api/v1/cart returns user cart")
    void testGetCart() throws Exception {
        UUID userId = UUID.randomUUID();
        CartResponse response = CartResponse.builder()
                .cartId(UUID.randomUUID())
                .userId(userId)
                .currency("VND")
                .items(Collections.emptyList())
                .subtotal(new MoneyDto("0.00", "VND"))
                .discountAmount(new MoneyDto("0.00", "VND"))
                .totalAmount(new MoneyDto("0.00", "VND"))
                .updatedAt(Instant.now())
                .build();

        when(cartService.getCartResponse(userId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/cart")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()));
    }

    @Test
    @DisplayName("POST /api/v1/cart/items adds item into cart")
    void testAddItem() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        AddToCartRequest req = AddToCartRequest.builder()
                .variantId(variantId)
                .sku("SHIRT-01")
                .productName("Oxford Shirt")
                .size("L")
                .color("Blue")
                .unitPrice(new BigDecimal("350000.00"))
                .quantity(1)
                .build();

        CartResponse response = CartResponse.builder()
                .cartId(UUID.randomUUID())
                .userId(userId)
                .currency("VND")
                .subtotal(new MoneyDto("350000.00", "VND"))
                .discountAmount(new MoneyDto("0.00", "VND"))
                .totalAmount(new MoneyDto("350000.00", "VND"))
                .items(Collections.emptyList())
                .build();

        when(cartService.addItem(eq(userId), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/cart/items")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal.amount").value("350000.00"));
    }

    @Test
    @DisplayName("POST /api/v1/cart/coupons applies coupon to cart")
    void testApplyCoupon() throws Exception {
        UUID userId = UUID.randomUUID();
        ApplyCouponRequest req = new ApplyCouponRequest("SUMMER10");

        CartResponse response = CartResponse.builder()
                .cartId(UUID.randomUUID())
                .userId(userId)
                .currency("VND")
                .coupon(new AppliedCouponDto("SUMMER10", new MoneyDto("50000.00", "VND")))
                .subtotal(new MoneyDto("500000.00", "VND"))
                .discountAmount(new MoneyDto("50000.00", "VND"))
                .totalAmount(new MoneyDto("450000.00", "VND"))
                .items(Collections.emptyList())
                .build();

        when(cartService.applyCoupon(userId, "SUMMER10")).thenReturn(response);

        mockMvc.perform(post("/api/v1/cart/coupons")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.code").value("SUMMER10"))
                .andExpect(jsonPath("$.discountAmount.amount").value("50000.00"));
    }

    @Test
    @DisplayName("GET /api/v1/cart/coupons returns available coupons")
    void testGetAvailableCoupons() throws Exception {
        CouponDto coupon = CouponDto.builder()
                .id(UUID.randomUUID())
                .code("WELCOME20")
                .description("Giảm 20%")
                .discountType("PERCENTAGE")
                .discountValue(new BigDecimal("20.00"))
                .build();

        when(couponService.getAvailableCoupons()).thenReturn(List.of(coupon));

        mockMvc.perform(get("/api/v1/cart/coupons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("WELCOME20"));
    }
}
