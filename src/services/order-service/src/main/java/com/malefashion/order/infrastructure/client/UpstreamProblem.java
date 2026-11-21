package com.malefashion.order.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ProblemDetail tối thiểu đọc từ cart/catalog để giữ nguyên status + code khi trả lỗi.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstreamProblem(String type, String title, Integer status, String detail, String code) {
}
