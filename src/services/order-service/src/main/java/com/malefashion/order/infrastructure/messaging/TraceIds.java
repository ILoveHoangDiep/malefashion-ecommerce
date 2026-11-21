package com.malefashion.order.infrastructure.messaging;

import org.slf4j.MDC;

import java.util.regex.Pattern;

/**
 * W3C trace context tối giản: traceId 32 hex lowercase, spanId 16 hex, flags 01.
 */
public final class TraceIds {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_ORDER_ID = "orderId";
    public static final String MDC_EVENT_ID = "eventId";

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    private TraceIds() {
    }

    /**
     * Dùng traceId hợp lệ đang có trong MDC để nối liền chuỗi xử lý; nếu không có thì sinh mới.
     */
    public static String currentOrNew() {
        String current = MDC.get(MDC_TRACE_ID);
        return isValid(current) ? current : newTraceId();
    }

    public static String newTraceId() {
        return randomHex(32);
    }

    public static boolean isValid(String traceId) {
        return traceId != null && TRACE_ID_PATTERN.matcher(traceId).matches();
    }

    /**
     * Sinh {@code traceparent} tại thời điểm publish; spanId không cần lưu DB.
     */
    public static String traceparent(String traceId) {
        if (!isValid(traceId)) {
            throw new IllegalArgumentException("traceId must be 32 lowercase hex characters");
        }
        return "00-" + traceId + "-" + randomHex(16) + "-01";
    }

    private static String randomHex(int length) {
        String value = java.util.UUID.randomUUID().toString().replace("-", "");
        while (value.length() < length) {
            value += java.util.UUID.randomUUID().toString().replace("-", "");
        }
        return value.substring(0, length);
    }
}
