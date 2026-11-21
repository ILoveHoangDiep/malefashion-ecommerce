package com.malefashion.order.domain.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Contract chỉ mang mili giây; mọi thời điểm được cắt trước khi lưu hoặc so sánh.
 */
public final class Instants {

    private Instants() {
    }

    public static Instant millis(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }
}
