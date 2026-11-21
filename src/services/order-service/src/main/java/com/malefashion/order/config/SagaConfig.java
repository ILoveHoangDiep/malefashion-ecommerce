package com.malefashion.order.config;

import com.malefashion.order.domain.saga.SagaStateMachine;
import com.malefashion.order.domain.saga.SagaTiming;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.UUID;

/**
 * Dựng state machine thuần từ cấu hình. Handler và deadline service dùng chung một bean để mọi
 * đường ghi có cùng cách sinh eventId và cùng đồng hồ.
 */
@Configuration
public class SagaConfig {

    @Bean
    public SagaStateMachine sagaStateMachine(OrderProperties properties, Clock clock) {
        OrderProperties.Saga saga = properties.saga();
        SagaTiming timing = new SagaTiming(
                saga.compensationTimeout(),
                saga.refundMaxRetries(),
                saga.refundRetryBackoff(),
                saga.refundRetryBackoffMultiplier(),
                saga.commitTimeout(),
                saga.commitMaxRetries(),
                saga.commitRetryBackoff(),
                saga.commitRetryBackoffMultiplier());
        return new SagaStateMachine(timing, clock, UUID::randomUUID);
    }
}
