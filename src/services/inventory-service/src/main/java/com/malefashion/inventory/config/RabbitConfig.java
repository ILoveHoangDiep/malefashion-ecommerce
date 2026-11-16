package com.malefashion.inventory.config;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

/**
 * Listener factory with a stateless retry interceptor: a message failing for infrastructure reasons is
 * retried in-process, then rejected without requeue so the broker routes it to the dead-letter queue.
 */
@Configuration
public class RabbitConfig {

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            InventoryProperties properties) {

        InventoryProperties.Retry retry = properties.messaging().retry();

        // Permanent problems (malformed envelope, wrong type header) must go straight to the DLQ.
        // The fourth argument matters: the map constructor defaults everything it does not mention to
        // "not retryable", which would send infrastructure failures to the DLQ after a single attempt.
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                retry.maxAttempts(),
                Map.of(AmqpRejectAndDontRequeueException.class, false),
                true,
                true);

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(
                RetryInterceptorBuilder.stateless()
                        .retryPolicy(retryPolicy)
                        .backOffOptions(retry.initialIntervalMs(), retry.multiplier(), retry.maxIntervalMs())
                        .recoverer(new RejectAndDontRequeueRecoverer())
                        .build()
        );
        return factory;
    }
}
