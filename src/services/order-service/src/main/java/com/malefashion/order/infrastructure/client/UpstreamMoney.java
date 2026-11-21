package com.malefashion.order.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Tiền trong response nội bộ là string decimal theo common.yaml; giữ string để việc kiểm định dạng
 * do order-service quyết định thay vì Jackson tự ép kiểu.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstreamMoney(String amount, String currency) {
}
