package com.malefashion.order.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Địa chỉ giao hàng; giới hạn độ dài khớp cột DB để lỗi dữ liệu trả 400 thay vì 500.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ShippingAddressRequest(
        @NotBlank @Size(max = 200) String recipientName,
        @NotBlank @Pattern(regexp = "^[0-9]{9,11}$") String phone,
        @NotBlank @Size(max = 300) String addressLine,
        @NotBlank @Size(max = 100) String ward,
        @NotBlank @Size(max = 100) String district,
        @NotBlank @Size(max = 100) String city
) {
}
