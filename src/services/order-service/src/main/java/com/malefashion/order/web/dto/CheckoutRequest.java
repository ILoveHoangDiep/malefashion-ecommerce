package com.malefashion.order.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body checkout theo order-api.yaml. additionalProperties=false nên field lạ bị từ chối 400 thay vì
 * âm thầm bỏ qua.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CheckoutRequest(
        @NotNull UUID cartId,
        @Size(min = 1, max = 50) String couponCode,
        @NotNull @Valid ShippingAddressRequest shippingAddress,
        @NotNull PaymentMethod paymentMethod,
        MockPaymentScenario mockPaymentScenario
) {
}
