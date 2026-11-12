package com.malefashion.payment.config;

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
 * Listener factory với retry interceptor không trạng thái: lỗi hạ tầng được thử lại trong process,
 * hết lượt thì reject không requeue để broker đưa vào dead-letter queue.
 */
@Configuration
public class RabbitConfig {

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            PaymentProperties properties) {

        PaymentProperties.Retry retry = properties.messaging().retry();

        // Lỗi vĩnh viễn phải đi thẳng DLQ. Tham số thứ tư rất quan trọng: constructor dùng map sẽ
        // mặc định mọi exception không được liệt kê là "không retry", khiến lỗi hạ tầng vào DLQ
        // ngay sau lần đầu. defaultValue=true giữ chúng retry được.
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
