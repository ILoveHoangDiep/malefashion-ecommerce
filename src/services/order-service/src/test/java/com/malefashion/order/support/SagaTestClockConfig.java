package com.malefashion.order.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Thay đồng hồ hệ thống bằng {@link MutableClock} để test hạn chờ xác định mà không phải ngủ.
 * {@code @Primary} thắng bean Clock mặc định nếu cả hai cùng tồn tại.
 */
@TestConfiguration
public class SagaTestClockConfig {

    public static final Instant NOW = Instant.parse("2026-10-22T03:15:30.123Z");

    @Bean
    @Primary
    public MutableClock sagaTestClock() {
        return new MutableClock(NOW, ZoneOffset.UTC);
    }
}
