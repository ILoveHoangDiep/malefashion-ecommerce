package com.malefashion.order.support;

import com.malefashion.order.config.OrderProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Bộ cấu hình hợp lệ tối thiểu cho unit test dựng bean thủ công.
 */
public final class TestOrderProperties {

    public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private TestOrderProperties() {
    }

    public static OrderProperties valid() {
        return valid(false);
    }

    public static OrderProperties valid(boolean mockScenarioEnabled) {
        return new OrderProperties(
                new OrderProperties.Saga(
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(2),
                        2,
                        Duration.ofMinutes(1),
                        2.0),
                new BigDecimal("30000.00"),
                5000,
                "test-internal-api-key",
                "http://cart-coupon-service:8083",
                "http://catalog-service:8082",
                mockScenarioEnabled,
                new OrderProperties.Outbox(
                        500,
                        100,
                        Duration.ofSeconds(5),
                        new OrderProperties.Outbox.Publisher(false)),
                new OrderProperties.Messaging(
                        "order-service",
                        new OrderProperties.Messaging.Listener(
                                false, 1, "order-service.saga.events"),
                        new OrderProperties.Messaging.Retry(3, 500, 2.0, 5000)));
    }
}
