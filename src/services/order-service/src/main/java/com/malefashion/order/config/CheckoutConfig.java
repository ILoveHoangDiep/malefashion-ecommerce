package com.malefashion.order.config;

import com.malefashion.order.domain.support.OrderNumberGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CheckoutConfig {

    @Bean
    public OrderNumberGenerator orderNumberGenerator(Clock clock) {
        return new OrderNumberGenerator(clock);
    }
}
