package com.malefashion.order.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.malefashion.order.config.JacksonConfig;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * ObjectMapper cho unit test, dùng đúng customizer của production để timestamp/money ra contract.
 */
public final class TestObjectMappers {

    private TestObjectMappers() {
    }

    public static ObjectMapper contract() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json()
                .modules(new JavaTimeModule());
        new JacksonConfig().instantContractCustomizer().customize(builder);
        return builder.build();
    }
}
