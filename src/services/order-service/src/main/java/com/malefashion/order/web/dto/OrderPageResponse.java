package com.malefashion.order.web.dto;

import java.util.List;

public record OrderPageResponse(
        List<OrderResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public OrderPageResponse {
        content = List.copyOf(content);
    }
}
