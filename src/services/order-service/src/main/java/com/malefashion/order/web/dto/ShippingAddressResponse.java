package com.malefashion.order.web.dto;

public record ShippingAddressResponse(
        String recipientName,
        String phone,
        String addressLine,
        String ward,
        String district,
        String city
) {
}
