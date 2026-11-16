package com.malefashion.cartcoupon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.cartcoupon.api.error.GlobalExceptionHandler;
import com.malefashion.cartcoupon.domain.model.Cart;
import com.malefashion.cartcoupon.domain.model.CartItem;
import com.malefashion.cartcoupon.dto.CartItemDto;
import com.malefashion.cartcoupon.dto.CartResponse;
import com.malefashion.cartcoupon.dto.MoneyDto;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InternalCartController.class)
@Import(GlobalExceptionHandler.class)
class InternalCartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CartService cartService;

    @MockBean
    private CouponService couponService;

    @Test
    @DisplayName("GET /internal/v1/carts/{userId} returns 200 with snapshot for checkout")
    void testGetCartForCheckout_Success() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        CartItem item = CartItem.builder()
                .variantId(variantId)
                .sku("SHIRT-01")
                .unitPrice(new BigDecimal("300000.00"))
                .quantity(1)
                .build();

        Cart cart = Cart.builder()
                .cartId(cartId)
                .userId(userId)
                .items(List.of(item))
                .build();

        CartResponse response = CartResponse.builder()
                .cartId(cartId)
                .userId(userId)
                .currency("VND")
                .items(List.of(CartItemDto.builder()
                        .variantId(variantId)
                        .sku("SHIRT-01")
                        .quantity(1)
                        .unitPrice(new MoneyDto("300000.00", "VND"))
                        .lineTotal(new MoneyDto("300000.00", "VND"))
                        .build()))
                .subtotal(new MoneyDto("300000.00", "VND"))
                .discountAmount(new MoneyDto("0.00", "VND"))
                .totalAmount(new MoneyDto("300000.00", "VND"))
                .updatedAt(Instant.now())
                .build();

        when(cartService.getCart(userId)).thenReturn(cart);
        when(cartService.mapToCartResponse(cart)).thenReturn(response);

        mockMvc.perform(get("/internal/v1/carts/{userId}", userId)
                        .header("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736")
                        .header("X-Caller-Service", "order-service")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.subtotal.amount").value("300000.00"))
                .andExpect(jsonPath("$.currency").value("VND"));
    }

    @Test
    @DisplayName("GET /internal/v1/carts/{userId} returns 404 ProblemDetail when cart does not exist")
    void testGetCartForCheckout_NotFound() throws Exception {
        UUID userId = UUID.randomUUID();
        when(cartService.getCart(userId)).thenReturn(null);

        mockMvc.perform(get("/internal/v1/carts/{userId}", userId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("CART_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /internal/v1/carts/{userId} returns 409 ProblemDetail when cart is empty")
    void testGetCartForCheckout_EmptyCart() throws Exception {
        UUID userId = UUID.randomUUID();
        Cart cart = Cart.builder()
                .cartId(UUID.randomUUID())
                .userId(userId)
                .items(Collections.emptyList())
                .build();

        when(cartService.getCart(userId)).thenReturn(cart);

        mockMvc.perform(get("/internal/v1/carts/{userId}", userId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("CART_EMPTY"))
                .andExpect(jsonPath("$.status").value(409));
    }
}
