package com.malefashion.order.web;

import java.time.Instant;
import java.util.List;

/**
 * Body lỗi RFC 9457 của contract common.yaml. Field khai báo đúng thứ tự ví dụ trong contract để
 * log/đối chiếu dễ; Jackson bỏ field null.
 */
public record ProblemResponse(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String traceId,
        Instant timestamp,
        List<ErrorDetail> errors
) {

    public static final String TYPE_BASE = "https://malefashion.local/errors/";

    public record ErrorDetail(String field, String message) {
    }

    public ProblemResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static String typeOf(String code) {
        return TYPE_BASE + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
