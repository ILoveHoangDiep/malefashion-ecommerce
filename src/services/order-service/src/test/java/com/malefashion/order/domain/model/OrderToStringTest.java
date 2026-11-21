package com.malefashion.order.domain.model;

import com.malefashion.order.support.OrderFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OrderToStringTest {

    @Test
    void doesNotExposeUserEmail() {
        Order order = OrderFixtures.newOrder(Instant.parse("2026-10-22T03:15:30.123Z"));

        assertThat(order.toString())
                .doesNotContain(OrderFixtures.USER_EMAIL)
                .doesNotContain("userEmail");
    }
}
