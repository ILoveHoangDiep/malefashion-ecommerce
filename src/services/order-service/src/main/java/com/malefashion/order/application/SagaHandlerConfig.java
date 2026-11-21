package com.malefashion.order.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 2b thêm bean {@link SagaEventHandler} thật; bean tạm tự động biến mất.
 */
@Configuration
public class SagaHandlerConfig {

    @Bean
    @ConditionalOnMissingBean(SagaEventHandler.class)
    public SagaEventHandler pendingSagaEventHandler() {
        return new PendingSagaEventHandler();
    }
}
