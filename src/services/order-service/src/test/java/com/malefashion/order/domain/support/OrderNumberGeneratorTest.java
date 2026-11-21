package com.malefashion.order.domain.support;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class OrderNumberGeneratorTest {

    @Test
    void generatesContractPatternUsingUtcDate() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T23:59:59.999Z"), ZoneOffset.UTC);
        OrderNumberGenerator generator = new OrderNumberGenerator(clock, new Random(42));

        String orderNumber = generator.next();

        assertThat(orderNumber).matches(OrderNumberGenerator.ORDER_NUMBER_PATTERN);
        assertThat(OrderNumberGenerator.ORDER_NUMBER_PATTERN.matcher(orderNumber).matches()).isTrue();
        assertThat(orderNumber).startsWith("MF-20261022-");
        // "MF-" (3) + YYYYMMDD (8) + "-" (1) + 6 ký tự = 18.
        assertThat(orderNumber).hasSize(18);
    }

    @Test
    void usesUtcDateEvenWhenClockHasAnotherZone() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T20:00:00.000Z"),
                ZoneOffset.ofHours(7));
        OrderNumberGenerator generator = new OrderNumberGenerator(clock, new Random(7));

        assertThat(generator.next()).startsWith("MF-20261022-");
    }

    @Test
    void advancesDateAtUtcMidnight() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-23T00:00:00.000Z"), ZoneOffset.UTC);
        OrderNumberGenerator generator = new OrderNumberGenerator(clock, new Random(1));

        assertThat(generator.next()).startsWith("MF-20261023-");
    }
}
